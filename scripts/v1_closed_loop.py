#!/usr/bin/env python3
"""Career OS v1.0 closed-loop acceptance.

Runs against a live instance and writes a case report under docs/audits/v1/.
It checks the v1.0 definition of done (docs/plans/2026-09-24-v1.0-closeout.md):

  1. trusted pool >= 10 jobs from >= 2 official sources
  2. >= 10 recommendation cases, each with per-rule eligibility and an official excerpt
  3. three real agent scenarios by a real model -- job discovery, eligibility explanation and
     job comparison -- each recorded with the user input, the tool chain, the data used, the
     evidence sources and the final output

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

# The three v1.0 acceptance scenarios. The later two continue the discovery session, so "第一个"
# and "第二个" refer to the list the user actually saw. If the model asks a clarifying question,
# the scenario answers it once with `reply` and records both turns.
SCENARIOS = (
    {"key": "discovery", "name": "岗位发现", "follows": None,
     "input": "杭州有哪些适合我的稳定技术岗位？", "reply": "杭州市区和各区县都可以，职位类别不限。"},
    {"key": "eligibility", "name": "资格解释", "follows": "discovery",
     "input": "第一个岗位我能报吗？具体卡在哪几条，依据是什么？", "reply": "就是你上面列出来的第一个。"},
    {"key": "comparison", "name": "岗位比较与决策辅助", "follows": "discovery",
     "input": "把第一个和第二个岗位比较一下，哪个更值得我先准备？", "reply": "就是你上面列出来的第一个和第二个。"},
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


def ok_steps(turn: dict, tool: str) -> list[dict]:
    return [step for step in turn.get("toolChain", []) if step["tool"] == tool and step["accepted"] and step["ok"]]


def scenario_checks(key: str, turns: list[dict]) -> list[tuple[str, bool]]:
    """What a scenario has to show. Only the last turn counts: an earlier turn may be the clarifying one."""
    last = turns[-1] if turns else {}
    checks = [
        ("真实模型规划", (last.get("plannerIdentity") or "").startswith("model:")),
        ("收尾通过执行器校验", last.get("outcome") == "FINISHED" and bool(last.get("narrative"))),
        ("引用了官方证据", bool(last.get("evidence"))),
    ]
    if key == "discovery":
        found = [step for step in ok_steps(last, "search_jobs") if (step.get("data") or {}).get("count", 0) > 0]
        checks.append(("search_jobs 查到了可信岗位", bool(found)))
    elif key == "eligibility":
        read = [step for step in ok_steps(last, "job_facts") if (step.get("data") or {}).get("rules")]
        checks.append(("job_facts 给出了逐条资格结论", bool(read)))
        checks.append(("引用了字段原文", any(ref.get("kind") == "FIELD" for ref in last.get("evidence", []))))
    elif key == "comparison":
        jobs = {(step.get("data") or {}).get("jobPostingId") for step in ok_steps(last, "job_facts")} - {None}
        checks.append(("job_facts 读了至少两个不同岗位", len(jobs) >= 2))
    return checks


def data_used(tool: str, data: dict) -> dict:
    """The part of a tool result the answer rests on, in a form a reviewer can read."""
    if tool == "search_jobs":
        return {"count": data.get("count"), "totalAvailable": data.get("totalAvailable"),
                "complete": data.get("complete"),
                "jobs": [{key: job.get(key) for key in ("jobPostingId", "jobTitle", "organizationName", "tier",
                                                         "eligibilityStatus", "recommendationStatus", "fitScore",
                                                         "stabilityScore", "applicationEndsOn")}
                         for job in data.get("jobs", [])]}
    if tool == "job_facts":
        return {key: data.get(key) for key in ("jobPostingId", "jobTitle", "organizationName", "tier",
                                               "eligibilityStatus", "recommendationStatus", "fitScore",
                                               "stabilityScore", "applicationEndsOn", "restrictions", "rules")}
    return {key: value for key, value in data.items() if key in ("count", "changedCount", "items")}


def one_turn(api: Api, candidate: str, text: str, session: str | None) -> dict:
    body = {"question": text}
    if session:
        body["sessionId"] = session
    status, response = api.call("POST", f"/api/v1/candidates/{candidate}/agent-runs", body)
    response = response if isinstance(response, dict) else {}
    turn = {"userInput": text, "httpStatus": status, "runId": response.get("runId"),
            "plannerIdentity": response.get("plannerIdentity"), "sessionId": response.get("sessionId"),
            "outcome": response.get("outcome"), "narrative": response.get("narrative"),
            "askedQuestion": response.get("question"), "violations": response.get("violations", []),
            "renderedJobs": response.get("jobs", []), "error": None if status == 200 else response.get("code")}
    if turn["runId"]:
        record = api.get(f"/api/v1/candidates/{candidate}/agent-runs/{turn['runId']}")
        turn["toolChain"] = [{"tool": step["tool"], "arguments": step.get("arguments"), "why": step.get("why"),
                              "accepted": step["accepted"], "reason": step.get("reason"),
                              "ok": bool(step.get("observationOk")), "summary": step.get("observationSummary"),
                              "data": step.get("observationData") or {}} for step in record.get("steps", [])]
        turn["dataUsed"] = [{"tool": step["tool"], **data_used(step["tool"], step["data"])}
                            for step in turn["toolChain"] if step["accepted"] and step["ok"]]
        turn["evidence"] = record.get("evidence") or [ref for step in record.get("steps", [])
                                                     for ref in step.get("evidence", [])]
        turn["modelTurns"] = record.get("modelTurns", [])
    return turn


def run_scenarios(api: Api, candidate: str) -> list[dict]:
    results: dict[str, dict] = {}
    for scenario in SCENARIOS:
        opened = results.get(scenario["follows"]) if scenario["follows"] else None
        session = opened["turns"][-1].get("sessionId") if opened and opened["turns"] else None
        turns = [one_turn(api, candidate, scenario["input"], session)]
        if turns[0]["httpStatus"] == 503:
            print(f"  {scenario['name']}：规划器不可用（{turns[0]['error']}）", flush=True)
        elif turns[0]["outcome"] == "ASKED_USER" and turns[0]["askedQuestion"]:
            turns.append(one_turn(api, candidate, scenario["reply"], turns[0].get("sessionId") or session))
        checks = scenario_checks(scenario["key"], turns)
        results[scenario["key"]] = {"key": scenario["key"], "name": scenario["name"], "turns": turns,
                                    "checks": checks, "passed": all(ok for _, ok in checks)}
        last = turns[-1]
        print(f"  {scenario['name']}：{'通过' if results[scenario['key']]['passed'] else '未通过'} · "
              f"{last.get('outcome') or last.get('error')} · {last.get('plannerIdentity')} · "
              f"{' → '.join(step['tool'] for step in last.get('toolChain', [])) or '无工具调用'}", flush=True)
    return list(results.values())


def definition_of_done(pool: list[dict], built: list[dict], scenarios: list[dict]) -> list[tuple[str, bool, str]]:
    source_hosts = {host(item.get("officialSourceUrl")) for item in pool} - {None}
    complete_cases = [case for case in built if case["rules"] and case["excerpts"] and case["officialSourceUrl"]]
    passed = [scenario["name"] for scenario in scenarios if scenario["passed"]]
    planners = sorted({turn.get("plannerIdentity") or "无" for scenario in scenarios for turn in scenario["turns"]})
    return [
        ("可信池 ≥ 10 个岗位", len(pool) >= 10, f"实际 {len(pool)}"),
        ("可信池来自 ≥ 2 个官方来源（按官方文件域名）", len(source_hosts) >= 2,
         f"实际 {len(source_hosts)}：{', '.join(sorted(source_hosts)) or '无'}"),
        ("≥ 10 个完整推荐案例（逐条资格 + 官方原文 + 官方链接）", len(complete_cases) >= 10,
         f"实际 {len(complete_cases)} / {len(built)}"),
        ("3 个真实 Agent 场景（发现、资格解释、比较）全部通过", len(scenarios) == len(SCENARIOS)
         and len(passed) == len(SCENARIOS),
         f"通过 {len(passed)} / {len(SCENARIOS)}：{', '.join(passed) or '无'}；规划器：{', '.join(planners) or '未运行'}"),
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
    lines += ["## Agent 场景验收案例", ""]
    if not report["scenarios"]:
        lines += ["未运行（--skip-agent）。", ""]
    for scenario in report["scenarios"]:
        lines += [f"### {scenario['name']}（{'通过' if scenario['passed'] else '未通过'}）", ""]
        lines += [f"- [{'x' if ok else ' '}] {name}" for name, ok in scenario["checks"]] + [""]
        for number, turn in enumerate(scenario["turns"], start=1):
            lines += [f"**第 {number} 轮 · 用户输入**：{turn['userInput']}", "",
                      f"- 规划器：`{turn.get('plannerIdentity') or '—'}` · 结局：{turn.get('outcome') or turn.get('error')}"
                      f" · 运行编号：`{turn.get('runId') or '—'}` · 模型往返 {len(turn.get('modelTurns', []))} 轮", "",
                      "**工具调用链**", "", "| # | 工具 | 参数 | 理由 | 结果 |", "|---:|---|---|---|---|"]
            for index, step in enumerate(turn.get("toolChain", []), start=1):
                arguments = ", ".join(f"{k}={v}" for k, v in (step.get("arguments") or {}).items()) or "—"
                result = step.get("summary") if step["accepted"] else f"被拒：{step.get('reason')}"
                lines.append(f"| {index} | {step['tool']} | {arguments} | {step.get('why') or '—'} | {result} |")
            lines += ["", "**使用的数据**", "", "```json",
                      json.dumps(turn.get("dataUsed", []), ensure_ascii=False, indent=2), "```", "",
                      "**证据来源**", ""]
            # 按岗位分组：比较两个岗位时，第二个岗位的证据不能被第一个的挤掉。
            titles = {job.get("jobPostingId"): job.get("jobTitle") for job in turn.get("renderedJobs", [])}
            grouped: dict[str, list[dict]] = {}
            for ref in turn.get("evidence", []):
                grouped.setdefault(ref.get("jobPostingId") or "—", []).append(ref)
            for job_id, refs in grouped.items():
                lines.append(f"- {titles.get(job_id) or job_id}")
                for ref in refs[:8]:
                    detail = f"{ref.get('field') or ''} {ref.get('excerpt') or ''}".strip()
                    lines.append(f"  - {ref.get('kind')} · {detail + ' · ' if detail else ''}"
                                 f"{locator(ref.get('locator')) + ' · ' if ref.get('locator') else ''}"
                                 f"{ref.get('sourceUrl') or ref.get('evidenceFragmentId')}")
                if len(refs) > 8:
                    lines.append(f"  - 另有 {len(refs) - 8} 条，见 JSON 报告")
            if not turn.get("evidence"):
                lines.append("- 无")
            lines += ["", "**最终输出**", ""]
            if turn.get("narrative"):
                lines.append(f"> {turn['narrative']}")
            if turn.get("askedQuestion"):
                lines.append(f"> 追问：{turn['askedQuestion']}")
            if turn.get("violations"):
                lines.append(f"> 收尾被执行器拒绝：{'；'.join(turn['violations'])}")
            for job in turn.get("renderedJobs", []):
                facts = " · ".join(str(value) for value in (
                    job.get("organizationName"), job.get("tier") and f"分层 {job['tier']}",
                    STATUS_LABELS.get(job.get("eligibilityStatus"), job.get("eligibilityStatus")),
                    job.get("fitScore") is not None and f"适配指数 {job['fitScore']}",
                    job.get("stabilityScore") is not None and f"稳定指数 {job['stabilityScore']}",
                    job.get("applicationEndsOn") and f"截止 {job['applicationEndsOn']}") if value)
                lines.append(f"- **{job.get('jobTitle')}** —— {facts}")
                lines += [f"  - 限制：{reason}" for reason in job.get("restrictions", [])]
            lines.append("")
    lines += ["完整记录（含每一轮模型原文）：`GET /api/v1/candidates/{candidateId}/agent-runs/{runId}`。", ""]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("CAREER_OS_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--candidate", default=os.environ.get("CAREER_OS_CANDIDATE", SEED_CANDIDATE))
    parser.add_argument("--run-sources", action="store_true", help="先对 5 个 P0 来源各跑一次增量采集")
    parser.add_argument("--reclassify", action="store_true",
                        help="按当前规则重新评估全部在招岗位的自动准入（人工决定保留），刷新初筛队列")
    parser.add_argument("--skip-agent", action="store_true", help="不跑 Agent 场景（完成定义第 4 项会记为未完成）")
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

    if not args.skip_agent:
        print("Agent 场景：", flush=True)
    report["scenarios"] = [] if args.skip_agent else run_scenarios(api, args.candidate)
    report["definitionOfDone"] = definition_of_done(pool, report["cases"], report["scenarios"])

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
