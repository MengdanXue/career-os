#!/usr/bin/env python3
"""CI 门禁：除白名单外，不允许任何测试被静默跳过。

无 Docker 时 Testcontainers 用例会被 Assumptions 跳过，而 Maven 依然打印 BUILD SUCCESS
——整个持久化层、迁移和并发正确性就这样在"绿色"构建里没有被验证过。
CI 上 Docker 一定可用，所以这些跳过必须是构建失败。

白名单里只允许放"依赖仓库外真实样本、无法提交"的验收用例。
"""
from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ElementTree

# 依赖本地官方样本工作簿（体积与来源许可原因未入库）的验收用例。
# 新增条目必须在评审中说明原因，其余任何跳过都视为回归。
ALLOWED_SKIPS = {
    "com.careeros.CareerOsApplicationTest.actualHangzhouUnifiedWorkbookCanBeImportedWhenFixtureIsAvailable",
    "com.careeros.CareerOsApplicationTest.actual2026UniversityWorkbookCanBeImportedWhenFixtureIsAvailable",
}


def main() -> int:
    reports = sorted(pathlib.Path(".").rglob("surefire-reports/TEST-*.xml"))
    if not reports:
        print("::error::no surefire reports found; the test phase did not run")
        return 1

    unexpected: list[tuple[str, str]] = []
    allowed_seen: set[str] = set()
    executed = 0

    for report in reports:
        for case in ElementTree.parse(report).getroot().iter("testcase"):
            executed += 1
            skipped = case.find("skipped")
            if skipped is None:
                continue
            # surefire 会把参数解析器签名拼进 name，例如 "someTest(OfficialExcelImportService)"
            method = (case.get("name") or "").split("(", 1)[0]
            name = f"{case.get('classname')}.{method}"
            if name in ALLOWED_SKIPS:
                allowed_seen.add(name)
                continue
            unexpected.append((name, skipped.get("message") or "no reason given"))

    print(f"parsed {len(reports)} report(s), {executed} test case(s)")
    for name in sorted(allowed_seen):
        print(f"allowed skip: {name}")

    if unexpected:
        for name, reason in sorted(unexpected):
            print(f"::error::test skipped in CI: {name} ({reason})")
        print(f"{len(unexpected)} test(s) were skipped unexpectedly")
        return 1

    print("no unexpected skips")
    return 0


if __name__ == "__main__":
    sys.exit(main())
