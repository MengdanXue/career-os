"""Unit tests for the v1.0 definition-of-done checks in scripts/v1_closed_loop.py."""
from __future__ import annotations

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "scripts" / "v1_closed_loop.py"
spec = importlib.util.spec_from_file_location("v1_closed_loop", SCRIPT)
v1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v1)


def pool(count: int, hosts: tuple[str, ...] = ("hrss.hangzhou.gov.cn", "rlsbt.zj.gov.cn")) -> list[dict]:
    return [{"jobId": f"job-{i}", "officialSourceUrl": f"https://{hosts[i % len(hosts)]}/jobs-{i}.xlsx"}
            for i in range(count)]


def case(complete: bool = True) -> dict:
    return {"rules": [{"rule": "AGE"}] if complete else [], "officialSourceUrl": "https://example.gov.cn/a.xlsx",
            "excerpts": [{"fieldName": "title", "excerpt": "信息中心", "sourceUrl": "https://example.gov.cn/a.xlsx"}]}


def step(tool: str, data: dict, accepted: bool = True, ok: bool = True) -> dict:
    return {"tool": tool, "arguments": {}, "why": "", "accepted": accepted, "reason": "ok", "ok": ok,
            "summary": "", "data": data}


def turn(identity: str = "model:deepseek-chat@api.deepseek.com", outcome: str = "FINISHED",
         narrative: str | None = "下面是这个岗位的情况。", chain: list[dict] | None = None,
         evidence: list[dict] | None = None) -> dict:
    return {"plannerIdentity": identity, "outcome": outcome, "narrative": narrative, "toolChain": chain or [],
            "evidence": [{"kind": "OFFICIAL_SOURCE", "sourceUrl": "https://example.gov.cn/a.xlsx"}]
            if evidence is None else evidence}


FIELD = {"kind": "FIELD", "field": "majorRequirementText", "excerpt": "计算机科学与技术",
         "sourceUrl": "https://example.gov.cn/a.xlsx"}


def passing_scenarios(identity: str = "model:deepseek-chat@api.deepseek.com") -> list[dict]:
    turns = {
        "discovery": turn(identity, chain=[step("search_jobs", {"count": 3})]),
        "eligibility": turn(identity, chain=[step("job_facts", {"jobPostingId": "a", "rules": [{"rule": "AGE"}]})],
                            evidence=[FIELD]),
        "comparison": turn(identity, chain=[step("job_facts", {"jobPostingId": "a"}),
                                            step("job_facts", {"jobPostingId": "b"})]),
    }
    result = []
    for key, value in turns.items():
        checks = v1.scenario_checks(key, [value])
        result.append({"key": key, "name": key, "turns": [value], "checks": checks,
                       "passed": all(ok for _, ok in checks)})
    return result


class DefinitionOfDoneTest(unittest.TestCase):
    def test_everything_holds_with_a_real_model(self):
        checks = v1.definition_of_done(pool(12), [case()] * 10, passing_scenarios())
        self.assertTrue(all(ok for _, ok, _ in checks), checks)

    def test_replayed_or_scripted_planners_never_count_as_a_real_model(self):
        for identity in ("recorded-replay", "scripted", "unlabelled"):
            checks = v1.definition_of_done(pool(12), [case()] * 10, passing_scenarios(identity))
            self.assertFalse(checks[3][1], identity)

    def test_skipping_the_agent_stage_is_not_done(self):
        self.assertFalse(v1.definition_of_done(pool(12), [case()] * 10, [])[3][1])

    def test_a_single_source_pool_fails_the_source_check(self):
        checks = v1.definition_of_done(pool(12, hosts=("hrss.hangzhou.gov.cn",)), [case()] * 10, [])
        self.assertTrue(checks[0][1])
        self.assertFalse(checks[1][1])

    def test_a_case_without_per_rule_results_is_not_complete(self):
        checks = v1.definition_of_done(pool(12), [case()] * 9 + [case(complete=False)], [])
        self.assertFalse(checks[2][1])

    def test_an_empty_pool_fails_everything_but_says_what_it_measured(self):
        checks = v1.definition_of_done([], [], [])
        self.assertFalse(any(ok for _, ok, _ in checks))
        self.assertIn("实际 0", checks[0][2])


class ScenarioChecksTest(unittest.TestCase):
    def failed(self, key: str, turns: list[dict]) -> list[str]:
        return [name for name, ok in v1.scenario_checks(key, turns) if not ok]

    def test_a_rejected_closing_does_not_pass(self):
        rejected = turn(narrative=None, chain=[step("search_jobs", {"count": 3})])
        self.assertIn("收尾通过执行器校验", self.failed("discovery", [rejected]))

    def test_discovery_needs_a_search_that_found_jobs(self):
        empty = turn(chain=[step("search_jobs", {"count": 0})])
        refused = turn(chain=[step("search_jobs", {"count": 3}, accepted=False)])
        self.assertIn("search_jobs 查到了可信岗位", self.failed("discovery", [empty]))
        self.assertIn("search_jobs 查到了可信岗位", self.failed("discovery", [refused]))

    def test_eligibility_needs_rules_and_a_field_excerpt(self):
        no_rules = turn(chain=[step("job_facts", {"jobPostingId": "a"})], evidence=[FIELD])
        no_excerpt = turn(chain=[step("job_facts", {"jobPostingId": "a", "rules": [{"rule": "AGE"}]})])
        self.assertIn("job_facts 给出了逐条资格结论", self.failed("eligibility", [no_rules]))
        self.assertIn("引用了字段原文", self.failed("eligibility", [no_excerpt]))

    def test_comparison_needs_two_different_jobs(self):
        same = turn(chain=[step("job_facts", {"jobPostingId": "a"}), step("job_facts", {"jobPostingId": "a"})])
        self.assertIn("job_facts 读了至少两个不同岗位", self.failed("comparison", [same]))

    def test_only_the_last_turn_counts_after_a_clarifying_question(self):
        asked = turn(outcome="ASKED_USER", narrative=None, evidence=[])
        answered = turn(chain=[step("search_jobs", {"count": 2})])
        self.assertEqual(self.failed("discovery", [asked, answered]), [])


class ReviewQueueTest(unittest.TestCase):
    def test_only_verified_jobs_with_known_identity_are_ready_for_review(self):
        matches = [
            {"jobId": "a", "admissionReasons": ["TARGET_TECHNICAL_ROLE"], "dataQualityStatus": "VERIFIED",
             "employmentIdentityConfirmed": True},
            {"jobId": "b", "admissionReasons": ["TARGET_TECHNICAL_ROLE", "HUMAN_SCOPE_INCLUDED"],
             "dataQualityStatus": "VERIFIED", "employmentIdentityConfirmed": True},
            {"jobId": "c", "admissionReasons": ["TARGET_TECHNICAL_ROLE"], "dataQualityStatus": "NORMALIZED",
             "employmentIdentityConfirmed": True, "qualitySummary": {"missingFields": ["applicationDeadline"]}},
            {"jobId": "d", "admissionReasons": ["TARGET_TECHNICAL_ROLE"], "dataQualityStatus": "VERIFIED",
             "employmentIdentityConfirmed": False},
        ]
        queue = v1.review_queue(matches)
        self.assertEqual((queue["included"], queue["readyForReview"], queue["notIncludable"]), (1, 1, 2))
        self.assertEqual(queue["topMissingFields"], [("applicationDeadline", 1)])


class LocatorTest(unittest.TestCase):
    def test_spreadsheet_and_pdf_locators_read_as_positions(self):
        self.assertEqual(v1.locator('{"sheet": "岗位表", "row": 12}'), "工作表「岗位表」 第 12 行")
        self.assertEqual(v1.locator('{"page": 3}'), "第 3 页")

    def test_unknown_locators_are_returned_unchanged(self):
        self.assertEqual(v1.locator("Sheet1!H12"), "Sheet1!H12")
        self.assertEqual(v1.locator(None), "位置未记录")


if __name__ == "__main__":
    unittest.main()
