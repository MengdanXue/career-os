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


def run(identity: str, recorded: bool = True) -> dict:
    return {"plannerIdentity": identity, "runId": "r" if recorded else None, "steps": [] if recorded else None}


class DefinitionOfDoneTest(unittest.TestCase):
    def test_everything_holds_with_a_real_model(self):
        checks = v1.definition_of_done(pool(12), [case()] * 10, [run("model:deepseek-chat@api.deepseek.com")] * 10)
        self.assertTrue(all(ok for _, ok, _ in checks), checks)

    def test_replayed_or_scripted_planners_never_count_as_a_real_model(self):
        for identity in ("recorded-replay", "scripted", "unlabelled"):
            checks = v1.definition_of_done(pool(12), [case()] * 10, [run(identity)] * 10)
            self.assertFalse(checks[3][1], identity)

    def test_a_run_missing_from_the_ledger_does_not_count(self):
        runs = [run("model:m@h")] * 9 + [run("model:m@h", recorded=False)]
        self.assertFalse(v1.definition_of_done(pool(12), [case()] * 10, runs)[3][1])

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
