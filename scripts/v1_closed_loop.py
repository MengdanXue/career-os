#!/usr/bin/env python3
"""Career OS v1.0 closed-loop acceptance.

Runs against a live instance and writes a case report under docs/audits/v1/.
It checks the v1.0 definition of done (docs/plans/2026-09-24-v1.0-closeout.md):

  1. trusted pool >= 10 jobs from >= 2 official sources
  2. >= 10 recommendation cases, each with per-rule eligibility and an official excerpt
  3. >= 10 agent runs by a real model, every step and its evidence in the run ledger

The script never admits jobs into the trusted pool: that is a reviewer decision made in
the UI (机会池 → 官网岗位初筛 → 纳入可信机会池). It reports how many jobs are ready for review.

Usage (credentials come from the environment, never from arguments):

  CAREER_OS_USER=... CAREER_OS_PASSWORD=... \\
    python scripts/v1_closed_loop.py --base-url http://localhost:8080 --run-sources --reclassify

Exit code 0 only when every DoD item holds. A replayed or scripted planner does not count as a
real model, and a skipped stage is reported as not done, never as passed.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

SEED_CANDIDATE = "01992f09-0000-7000-8000-000000000001"
P0_SOURCES = ("ZJ_HRSS_INSTITUTION", "HZ_HRSS_INSTITUTION", "HZ_XIHU_GOV", "HZ_FIRST_HOSPITAL", "HZ_DATA_GROUP")

# Ten fixed user tasks. A value of ("follow", n) continues the session opened by task n (1-based).
TASKS = (
    ("杭州有哪些值得我现在关注的稳定技术岗位？", None),
    ("第二个岗位怎么样？", ("follow", 1)),
    ("只看 T1 的岗位有哪些？", None),
    ("哪些岗位还需要我先确认资料？", None),
    ("我关注的岗位有没有变化？", None),
    ("信息系统类的岗位有哪些？", None),
    ("第一个岗位的限制条件是什么？", ("follow", 6)),
    ("西湖区有没有合适的岗位？", None),
    ("宁波有什么合适的？", None),
    ("数据类岗位里哪个最稳定？", None),
)

RULE_LABELS = {
    "AGE": "年龄", "EDUCATION": "学历", "EXACT_MAJOR": "专业", "GRADUATE_YEAR": "毕业届别",
    "EXPERIENCE": "工作经历", "PROFESSIONAL_TITLE": "职称", "POLITICAL_AFFILIATION": "政治面貌",
    "GENDER": "性别", "FRESH_GRADUATE_STATUS": "应届身份",
}
STATUS_LABELS = {
    "ELIGIBLE": "符合", "CONDITIONAL": "条件符合", "NEEDS_CONFIRMATION": "待确认",
    "CONFLICTING_EVIDENCE": "证据冲突", "INELIGIBLE": "不符合",
}


class Api:
    def __init__(self, base_url: str, user: str | None, password: str | None, timeout: float):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.headers = {"Accept": "application/json"}
        if user and password:
            token = base64.b64encode(f"{user}:{password}".encode()).decode()
            self.headers["Authorization"] = f"Basic {token}"

    def call(self, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = dict(self.headers)
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as failure:
            raw = failure.read()
            try:
                return failure.code, json.loads(raw) if raw else None
            except ValueError:
                return failure.code, {"detail": raw.decode("utf-8", "replace")[:300]}

    def get(self, path: str) -> object:
        status, body = self.call("GET", path)
        if status != 200:
            raise RuntimeError(f"GET {path} -> {status}: {body}")
        return body


def paged(api: Api, path: str, size: int = 100) -> list[dict]:
    items, page = [], 0
    separator = "&" if "?" in path else "?"
    while True:
        body = api.get(f"{path}{separator}page={page}&size={size}")
        items.extend(body.get("items", []))
        if (page + 1) * size >= body.get("total", 0) or not body.get("items"):
            return items
        page += 1


def host(url: str | None) -> str | None:
    return urllib.parse.urlparse(url).hostname if url else None


def run_sources(api: Api) -> list[dict]:
    sources = {source["code"]: source for source in api.get("/api/acquisition/sources")}
    results = []
    for code in P0_SOURCES:
        source = sources.get(code)
        if not source or not source.get("id"):
            results.append({"code": code, "status": "NOT_CONFIGURED"})
            continue
        status, body = api.call("POST", f"/api/acquisition/sources/{source['id']}/runs")
        body = body if isinstance(body, dict) else {}
        results.append({"code": code, "httpStatus": status, "status": body.get("status"),
                        **{key: body.get(key) for key in ("discoveredCount", "fetchedCount", "addedCount",
                                                           "updatedCount", "unchangedCount", "failedCount",
                                                           "errorCode", "errorMessage")}})
        print(f"  {code}: {results[-1]['status']} (HTTP {status})", flush=True)
    return results


def review_queue(matches: list[dict]) -> dict:
    included = [m for m in matches if "HUMAN_SCOPE_INCLUDED" in m.get("admissionReasons", [])]
    ready = [m for m in matches if m not in included and m.get("dataQualityStatus") == "VERIFIED"
             and m.get("employmentIdentityConfirmed")]
    missing: dict[str, int] = {}
    for match in matches:
        for field in (match.get("qualitySummary") or {}).get("missingFields", []):
            missing[field] = missing.get(field, 0) + 1
    return {"prescreened": len(matches), "included": len(included), "readyForReview": len(ready),
            "notIncludable": len(matches) - len(included) - len(ready),
            "topMissingFields": sorted(missing.items(), key=lambda item: -item[1])[:5]}


def cases(decisions: list[dict], matches: dict[str, dict], top: int) -> list[dict]:
    result = []
    for decision in decisions[:top]:
        match = matches.get(decision["jobId"], {})
        excerpts = [ref for ref in (match.get("qualitySummary") or {}).get("evidenceReferences", [])
                    if ref.get("excerpt") and ref.get("sourceUrl")]
        result.append({
            "jobId": decision["jobId"], "jobTitle": decision.get("jobTitle"),
            "organizationName": decision.get("organizationName"), "location": decision.get("location"),
            "tier": decision.get("tier"), "eligibilityStatus": decision.get("eligibilityStatus"),
            "recommendationStatus": decision.get("recommendationStatus"),
            "fitScore": (decision.get("fit") or {}).get("score"),
            "stabilityScore": (decision.get("stability") or {}).get("score"),
            "rules": decision.get("eligibilityRules", []), "warnings": decision.get("warnings", []),
            "officialSourceUrl": decision.get("officialSourceUrl"),
            "announcementUrl": match.get("sourceUrl"), "applicationEndsOn": match.get("applicationEndsOn"),
            "excerpts": excerpts[:6], "explanation": decision.get("explanation"),
        })
    return result


def agent_runs(api: Api, candidate: str) -> list[dict]:
    runs: list[dict] = []
    for index, (question, follow) in enumerate(TASKS, start=1):
        body = {"question": question}
        if follow:
            opened = runs[follow[1] - 1]
            if opened.get("sessionId"):
                body["sessionId"] = opened["sessionId"]
        status, response = api.call("POST", f"/api/v1/candidates/{candidate}/agent-runs", body)
        response = response if isinstance(response, dict) else {}
        entry = {"task": index, "question": question, "httpStatus": status,
                 "outcome": response.get("outcome"), "plannerIdentity": response.get("plannerIdentity"),
                 "runId": response.get("runId"), "sessionId": response.get("sessionId"),
                 "narrative": response.get("narrative"), "askedQuestion": response.get("question"),
                 "violations": response.get("violations", []), "error": response.get("code")}
        if status == 503:
            entry["error"] = response.get("code", "PLANNER_UNAVAILABLE")
            runs.append(entry)
            print(f"  任务 {index}: 规划器不可用（{entry['error']}），停止 Agent 阶段", flush=True)
            break
        if entry["runId"]:
            record = api.get(f"/api/v1/candidates/{candidate}/agent-runs/{entry['runId']}")
            entry["steps"] = [{"tool": step["tool"], "arguments": step.get("arguments"),
                               "accepted": step["accepted"], "reason": step.get("reason"),
                               "evidence": len(step.get("evidence", []))} for step in record.get("steps", [])]
            entry["evidenceCount"] = sum(step["evidence"] for step in entry["steps"])
            entry["modelTurns"] = len(record.get("modelTurns", []))
        runs.append(entry)
        print(f"  任务 {index}: {entry['outcome']} · {entry.get('plannerIdentity')} · "
              f"{len(entry.get('steps', []))} 步 · 证据 {entry.get('evidenceCount', 0)}", flush=True)
    return runs


def definition_of_done(pool: list[dict], built: list[dict], runs: list[dict]) -> list[tuple[str, bool, str]]:
    source_hosts = {host(item.get("officialSourceUrl")) for item in pool} - {None}
    complete_cases = [case for case in built if case["rules"] and case["excerpts"] and case["officialSourceUrl"]]
    model_runs = [run for run in runs if (run.get("plannerIdentity") or "").startswith("model:")]
    recorded = [run for run in model_runs if run.get("runId") and run.get("steps") is not None]
    return [
        ("可信池 ≥ 10 个岗位", len(pool) >= 10, f"实际 {len(pool)}"),
        ("可信池来自 ≥ 2 个官方来源（按官方文件域名）", len(source_hosts) >= 2,
         f"实际 {len(source_hosts)}：{', '.join(sorted(source_hosts)) or '无'}"),
        ("≥ 10 个完整推荐案例（逐条资格 + 官方原文 + 官方链接）", len(complete_cases) >= 10,
         f"实际 {len(complete_cases)} / {len(built)}"),
        ("真实模型运行 ≥ 10 次且全部在台账里", len(recorded) >= 10,
         f"模型运行 {len(model_runs)}，已入台账 {len(recorded)}；"
         f"规划器：{', '.join(sorted({run.get('plannerIdentity') or '无' for run in runs})) or '未运行'}"),
    ]


def locator(raw: str | None) -> str:
    """把证据定位读成人话：岗位表第几行、PDF 第几页；读不懂就原样给出，不猜。"""
    if not raw:
        return "位置未记录"
    try:
        value = json.loads(raw)
    except ValueError:
        return raw
    if not isinstance(value, dict):
        return raw
    parts = []
    if value.get("sheet"):
        parts.append(f"工作表「{value['sheet']}」")
    if value.get("row") is not None:
        parts.append(f"第 {value['row']} 行")
    if value.get("page") is not None:
        parts.append(f"第 {value['page']} 页")
    return " ".join(parts) or raw


def markdown(report: dict) -> str:
    lines = [f"# Career OS v1.0 闭环验收 {report['generatedAt'][:10]}", "",
             f"- 实例：`{report['baseUrl']}`", f"- 候选人：`{report['candidateId']}`", "", "## 完成定义", ""]
    for name, ok, detail in report["definitionOfDone"]:
        lines.append(f"- [{'x' if ok else ' '}] {name} —— {detail}")
    lines += ["", "## 数据与准入", "", f"- 岗位库：`{json.dumps(report['library'], ensure_ascii=False)}`",
              f"- 初筛队列：`{json.dumps(report['reviewQueue'], ensure_ascii=False)}`"]
    if report.get("sourceRuns"):
        lines += ["", "| 来源 | 状态 | 发现 | 新增 | 更新 | 未变 | 失败 |", "|---|---|---:|---:|---:|---:|---:|"]
        for run in report["sourceRuns"]:
            lines.append(f"| {run['code']} | {run.get('status')} | {run.get('discoveredCount')} | "
                         f"{run.get('addedCount')} | {run.get('updatedCount')} | {run.get('unchangedCount')} | "
                         f"{run.get('failedCount')} |")
    lines += ["", f"## 推荐案例（可信池前 {len(report['cases'])} 个）", ""]
    if not report["cases"]:
        lines.append("可信池为空：先在“机会池 → 官网岗位初筛”里由复核人纳入证据齐全的岗位。")
    for number, case in enumerate(report["cases"], start=1):
        lines += [f"### {number}. {case['jobTitle']} — {case['organizationName']}", "",
                  f"- 分层 {case['tier']} · 资格 {STATUS_LABELS.get(case['eligibilityStatus'], case['eligibilityStatus'])}"
                  f" · 建议 {case['recommendationStatus']} · 适配 {case['fitScore']} · 稳定 {case['stabilityScore']}"
                  f" · 报名截止 {case.get('applicationEndsOn') or '未明确'}",
                  f"- 官方文件：{case['officialSourceUrl']}", "", "| 规则 | 结论 | 说明 | 证据片段 |", "|---|---|---|---|"]
        for rule in case["rules"]:
            lines.append(f"| {RULE_LABELS.get(rule['rule'], rule['rule'])} | "
                         f"{STATUS_LABELS.get(rule['status'], rule['status'])} | {rule['explanation']} | "
                         f"{len(rule.get('evidenceIds', []))} |")
        lines.append("")
        for ref in case["excerpts"]:
            # 每条原文单独成段：连续的引用行会被 Markdown 合并成一段，分不清哪句对应哪个字段。
            lines += [f"> **{ref['fieldName']}**：{ref['excerpt']}  ",
                      f"> {locator(ref.get('locator'))} · {ref['sourceUrl']}", ""]
    lines += ["## Agent 运行", "", "| # | 问题 | 结局 | 规划器 | 步骤 | 证据 | 运行编号 |", "|---:|---|---|---|---|---:|---|"]
    for run in report["agentRuns"]:
        steps = " → ".join(("" if step["accepted"] else "✗") + step["tool"] for step in run.get("steps", [])) or "—"
        lines.append(f"| {run['task']} | {run['question']} | {run.get('outcome') or run.get('error')} | "
                     f"{run.get('plannerIdentity') or '—'} | {steps} | {run.get('evidenceCount', 0)} | "
                     f"`{run.get('runId') or '—'}` |")
    lines += ["", "完整步骤、每一步的证据与模型原文：`GET /api/v1/candidates/{candidateId}/agent-runs/{runId}`。", ""]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("CAREER_OS_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--candidate", default=os.environ.get("CAREER_OS_CANDIDATE", SEED_CANDIDATE))
    parser.add_argument("--run-sources", action="store_true", help="先对 5 个 P0 来源各跑一次增量采集")
    parser.add_argument("--reclassify", action="store_true",
                        help="按当前规则重新评估全部在招岗位的自动准入（人工决定保留），刷新初筛队列")
    parser.add_argument("--skip-agent", action="store_true", help="不跑 Agent 任务（完成定义第 3 项会记为未完成）")
    parser.add_argument("--top", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--out", default=str(pathlib.Path(__file__).resolve().parent.parent / "docs" / "audits" / "v1"))
    args = parser.parse_args(argv)

    api = Api(args.base_url, os.environ.get("CAREER_OS_USER"), os.environ.get("CAREER_OS_PASSWORD"), args.timeout)
    status, health = api.call("GET", "/actuator/health")
    if status != 200:
        print(f"实例不可用：{args.base_url} -> {status} {health}", file=sys.stderr)
        return 2
    if not api.headers.get("Authorization"):
        print("需要 CAREER_OS_USER 与 CAREER_OS_PASSWORD：脚本不带默认口令。", file=sys.stderr)
        return 2

    report: dict = {"generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "baseUrl": args.base_url,
                    "candidateId": args.candidate, "p0Sources": list(P0_SOURCES)}
    if args.run_sources:
        print("采集 P0 来源：", flush=True)
        report["sourceRuns"] = run_sources(api)
    if args.reclassify:
        status, body = api.call("POST", "/api/v1/job-admissions/reclassifications")
        if status != 200:
            print(f"重新评估失败：HTTP {status} {body}", file=sys.stderr)
            return 2
        report["reclassification"] = body
        print(f"重新评估 {body['processed']} 个在招岗位，可纳入待复核 {body['readyForReview']} 个", flush=True)
    report["library"] = api.get("/api/v1/job-library/summary")

    matches = paged(api, f"/api/v1/candidates/{args.candidate}/job-matches")
    report["reviewQueue"] = review_queue(matches)
    pool = paged(api, f"/api/v1/candidates/{args.candidate}/job-decisions")
    print(f"可信池 {len(pool)} 个；初筛 {report['reviewQueue']['prescreened']} 个，"
          f"其中可纳入待复核 {report['reviewQueue']['readyForReview']} 个", flush=True)
    report["cases"] = cases(pool, {match["jobId"]: match for match in matches}, args.top)

    report["agentRuns"] = [] if args.skip_agent else agent_runs(api, args.candidate)
    report["definitionOfDone"] = definition_of_done(pool, report["cases"], report["agentRuns"])

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y-%m-%d-%H%M%S")
    (out / f"{stamp}-v1-closed-loop.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    (out / f"{stamp}-v1-closed-loop.md").write_text(markdown(report), "utf-8")
    print(f"\n报告：{out / f'{stamp}-v1-closed-loop.md'}\n")
    for name, ok, detail in report["definitionOfDone"]:
        print(f"  [{'通过' if ok else '未完成'}] {name} —— {detail}")
    return 0 if all(ok for _, ok, _ in report["definitionOfDone"]) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
