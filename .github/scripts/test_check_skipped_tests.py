"""Exercise the actual CLI against isolated Surefire reports, without Maven."""
import pathlib
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from check_skipped_tests import ALLOWED_SKIPS, MODULES

SCRIPT = pathlib.Path(__file__).with_name("check_skipped_tests.py").resolve()


class ReportGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)

    def report(self, module, name="example.Tests.passes", skip=None, failure=None, **summary):
        directory = self.root / module / "target" / "surefire-reports"
        directory.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", {"failures": "0", "errors": "0", **summary})
        classname, method = name.rsplit(".", 1)
        case = ET.SubElement(suite, "testcase", {"classname": classname, "name": method})
        if skip is not None:
            ET.SubElement(case, "skipped", {"message": skip})
        if failure:
            ET.SubElement(case, failure)
        path = directory / f"TEST-{len(list(directory.glob('*.xml')))}.xml"
        ET.ElementTree(suite).write(path, encoding="utf-8")
        return path

    def baseline(self):
        for module in MODULES:
            self.report(module)

    def check(self, expected, contains=""):
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.root)],
                                capture_output=True, text=True, check=False)
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        self.assertIn(contains, result.stdout)

    def test_no_reports_fails(self):
        self.check(1, "no test reports found")

    def test_missing_module_fails(self):
        self.report(MODULES[0])
        self.check(1, "career-web: no test reports")

    def test_all_modules_executed_passes(self):
        self.baseline()
        self.check(0, "4 non-skipped")

    def test_unexpected_skip_fails(self):
        self.baseline()
        self.report(MODULES[2], skip="disabledWithoutDocker is true")
        self.check(1, "unexpected skip")

    def test_all_audited_skips_pass_with_exact_reason_and_parameter_signature(self):
        self.baseline()
        for name, reason in ALLOWED_SKIPS.items():
            self.report(MODULES[3], name + "(MockMvc, ObjectMapper)", skip=reason)
        self.check(0, "9 skipped")

    def test_allowed_method_does_not_hide_docker_failure(self):
        self.baseline()
        self.report(MODULES[3], next(iter(ALLOWED_SKIPS)), skip="Docker is not available")
        self.check(1, "unexpected skip")

    def test_only_allowed_skips_still_fails_without_execution(self):
        for module in MODULES:
            name, reason = next(iter(ALLOWED_SKIPS.items()))
            self.report(module, name, skip=reason)
        self.check(1, "no test cases executed")

    def test_failure_and_error_nodes_fail(self):
        for node in ("failure", "error"):
            with self.subTest(node=node):
                self.baseline()
                self.report(MODULES[1], failure=node)
                self.check(1, "test failed or errored")

    def test_suite_failure_without_case_failure_fails(self):
        self.baseline()
        self.report(MODULES[0], failures="1")
        self.check(1, "suite failures=1")

    def test_malformed_report_fails(self):
        self.baseline()
        path = self.report(MODULES[0])
        path.write_text("<testsuite>", encoding="utf-8")
        self.check(1, "unreadable test report")


if __name__ == "__main__":
    unittest.main()
