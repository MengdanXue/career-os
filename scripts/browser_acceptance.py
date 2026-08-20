"""Browser acceptance check for the locally running Career OS workbench."""

from __future__ import annotations

import json
import os
from pathlib import Path

from playwright.sync_api import sync_playwright


BASE_URL = os.environ.get("CAREER_OS_BASE_URL", "http://localhost:8080")
PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = PROJECT_ROOT / "output" / "playwright"


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    console_errors: list[str] = []
    server_errors: list[str] = []
    checkpoints: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        page = context.new_page()
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on("response", lambda response: server_errors.append(f"{response.status} {response.url}") if response.status >= 500 else None)

        page.goto(BASE_URL, wait_until="networkidle")
        page.evaluate("localStorage.clear()")
        page.reload(wait_until="networkidle")
        page.get_by_role("heading", name="先确认你的决策资料").wait_for()
        page.screenshot(path=OUTPUT_DIR / "profile-desktop.png", full_page=True)
        checkpoints.append("首次使用会要求确认候选人事实")

        page.get_by_role("button", name="确认并开始").click()
        page.locator(".today-page").wait_for()
        page.get_by_role("link", name="机会池", exact=True).click()
        page.wait_for_load_state("networkidle")
        page.get_by_role("heading", name="机会池", exact=True).wait_for()
        checkpoints.append("机会池可从主导航打开")

        page.get_by_role("link", name="更新岗位库", exact=True).click()
        page.wait_for_load_state("networkidle")
        page.get_by_role("heading", name="更新岗位库", exact=True).wait_for()
        checkpoints.append("官方源、导入和复核工作台可打开")

        page.get_by_role("button", name="打开 Career OS 决策助手").click()
        page.get_by_role("button", name="本周最值得准备什么？").click()
        page.get_by_role("button", name="分析", exact=True).click()
        page.locator(".agent-panel").wait_for()
        page.locator(".agent-answer").wait_for(timeout=15_000)
        checkpoints.append("无模型模式下 Agent 仍能返回受控建议")
        page.screenshot(path=OUTPUT_DIR / "updates-agent-desktop.png", full_page=True)

        mobile = context.new_page()
        mobile.set_viewport_size({"width": 390, "height": 844})
        mobile.goto(f"{BASE_URL}/", wait_until="networkidle")
        mobile.locator(".today-page").wait_for()
        overflow = mobile.evaluate("document.documentElement.scrollWidth > document.documentElement.clientWidth")
        if overflow:
            raise AssertionError("移动端页面出现水平溢出")
        mobile.screenshot(path=OUTPUT_DIR / "today-mobile.png", full_page=True)
        checkpoints.append("390px 移动端无水平溢出")

        browser.close()

    if console_errors:
        raise AssertionError(f"浏览器控制台错误：{console_errors}")
    if server_errors:
        raise AssertionError(f"页面请求出现服务端错误：{server_errors}")

    report = {"baseUrl": BASE_URL, "checkpoints": checkpoints, "screenshots": [
        str(OUTPUT_DIR / "profile-desktop.png"),
        str(OUTPUT_DIR / "updates-agent-desktop.png"),
        str(OUTPUT_DIR / "today-mobile.png"),
    ]}
    (OUTPUT_DIR / "acceptance-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
