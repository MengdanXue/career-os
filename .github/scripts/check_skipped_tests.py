#!/usr/bin/env python3
"""Require real test execution in every Java module; permit only audited skips.

The five local sample files are outside the repository. The four live smoke
methods call real official websites and require CAREER_OS_LIVE_SMOKE=true.
Neither exception permits a Docker failure, a new test, or another skip reason.
Maven's test phase also runs the frontend type checks and Vitest suite.
"""
from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ET

MODULES = ("career-domain", "career-application", "career-infrastructure", "career-web")
ALLOWED_SKIPS = {
    "com.careeros.CareerOsApplicationTest.actualHangzhouUnifiedWorkbookCanBeImportedWhenFixtureIsAvailable":
        "Assumption failed: local official workbook fixture is not available",
    "com.careeros.CareerOsApplicationTest.actual2026UniversityWorkbookCanBeImportedWhenFixtureIsAvailable":
        "Assumption failed: local 2026 university workbook fixture is not available",
    "com.careeros.ExtractionEndToEndTest.realPdfGuideProducesEvidenceAndDoesNotCreateFormalJobs":
        "Assumption failed: local official PDF fixture is not available",
    "com.careeros.ExtractionEndToEndTest.repeatRealHtmlReusesTheSameExtractionRun":
        "Assumption failed: local official HTML fixture is not available",
    "com.careeros.ExtractionEndToEndTest.concurrentIdenticalOfficialHtmlCreatesOnePostgresRun":
        "Assumption failed: local official concurrent HTML fixture is not available",
    "com.careeros.infrastructure.acquisition.GovernmentSoeOfficialLiveSmokeTest.capitalAndMetroOfficialApisReturnCurrentCanonicalRecruitmentLinks":
        "Environment variable [CAREER_OS_LIVE_SMOKE] does not exist",
    "com.careeros.infrastructure.acquisition.TongluOfficialLiveSmokeTest.officialSearchExposesTheCurrentRecruitmentLifecycleAsCanonicalGovernmentLinks":
        "Environment variable [CAREER_OS_LIVE_SMOKE] does not exist",
    "com.careeros.infrastructure.acquisition.UcasHangzhouOfficialLiveSmokeTest.auditedOfficialHttpArchiveExposesRecruitmentAndLifecycleEvidence":
        "Environment variable [CAREER_OS_LIVE_SMOKE] does not exist",
    "com.careeros.infrastructure.acquisition.WestlakeOfficialLiveSmokeTest.officialEngineeringDatasetExposesRelevant2024Through2026RecruitmentDetails":
        "Environment variable [CAREER_OS_LIVE_SMOKE] does not exist",
}
# Some Surefire/JUnit combinations omit an AssumptionFailure message from
# the XML report.  These are the same five repository-external fixture tests
# above; an empty reason is acceptable only for this exact allow-list.
ALLOWED_EMPTY_REASON = {
    "com.careeros.CareerOsApplicationTest.actualHangzhouUnifiedWorkbookCanBeImportedWhenFixtureIsAvailable",
    "com.careeros.CareerOsApplicationTest.actual2026UniversityWorkbookCanBeImportedWhenFixtureIsAvailable",
    "com.careeros.ExtractionEndToEndTest.realPdfGuideProducesEvidenceAndDoesNotCreateFormalJobs",
    "com.careeros.ExtractionEndToEndTest.repeatRealHtmlReusesTheSameExtractionRun",
    "com.careeros.ExtractionEndToEndTest.concurrentIdenticalOfficialHtmlCreatesOnePostgresRun",
}


def main(root: pathlib.Path) -> int:
    errors = []
    total = passed = skipped_count = 0
    for module in MODULES:
        reports = sorted((root / module / "target" / "surefire-reports").glob("TEST-*.xml"))
        reports += sorted((root / module / "target" / "failsafe-reports").glob("TEST-*.xml"))
        if not reports:
            errors.append(f"{module}: no test reports found; the test phase did not run")
            continue
        module_executed = 0
        for report in reports:
            try:
                document = ET.parse(report).getroot()
            except (ET.ParseError, OSError) as exc:
                errors.append(f"{report}: unreadable test report: {exc}")
                continue
            # Check summary counters as well, including runner/setup failures
            # that do not have a corresponding testcase failure element.
            for suite in document.iter("testsuite"):
                for counter in ("failures", "errors"):
                    try:
                        count = int(suite.get(counter, "0"))
                        if count != 0:
                            errors.append(f"{report}: suite {counter}={count}")
                    except ValueError:
                        errors.append(f"{report}: invalid {counter} counter")
            for case in document.iter("testcase"):
                total += 1
                method = (case.get("name") or "").split("(", 1)[0]
                name = f"{case.get('classname')}.{method}"
                skip = case.find("skipped")
                if case.find("failure") is not None or case.find("error") is not None:
                    errors.append(f"{name}: test failed or errored")
                if skip is not None:
                    skipped_count += 1
                    reason = skip.get("message") or "no reason given"
                    if ALLOWED_SKIPS.get(name) != reason and not (name in ALLOWED_EMPTY_REASON and reason == "no reason given"):
                        errors.append(f"{name}: unexpected skip ({reason})")
                    else:
                        print(f"allowed skip: {name} ({reason})")
                else:
                    module_executed += 1
                    passed += 1
        if module_executed == 0:
            errors.append(f"{module}: no test cases executed")
    print(f"{total} test cases; {passed} non-skipped; {skipped_count} skipped")
    for error in errors:
        print(f"::error::{error}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")))
