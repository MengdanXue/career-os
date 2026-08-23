"""Browser acceptance check for the personal semi-public career planner."""

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

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on("response", lambda response: server_errors.append(f"{response.status} {response.url}") if response.status >= 500 else None)

        page.goto(f"{BASE_URL}/plan", wait_until="networkidle")
        page.get_by_role("heading", name="我的半体制规划").wait_for()
        if page.locator(".scenario-rail > li").count() != 3:
            raise AssertionError("规划页没有展示完整的三个资格场景")
        if page.locator(".route-card").count() < 4:
            raise AssertionError("规划页没有展示完整的四条路线")

        detail_link = page.locator(".evidence-list h3 a").first
        if not detail_link.is_visible():
            raise AssertionError("规划页没有可下钻的官网历史岗位")
        detail_link.click()
        page.wait_for_load_state("networkidle")
        page.get_by_text("历史岗位事实，不代表当前仍可报名", exact=True).wait_for()
        page.get_by_role("heading", name="能不能报，看这些原始条件").wait_for()
        page.get_by_role("heading", name="当年什么时候、怎么考").wait_for()
        if page.locator(".planning-official-links a[href]").count() < 2:
            raise AssertionError("岗位详情没有同时保留官方公告与附件入口")

        screenshot = OUTPUT_DIR / "career-plan-job-detail.png"
        page.screenshot(path=screenshot, full_page=True)
        browser.close()

    if console_errors:
        raise AssertionError(f"浏览器控制台错误：{console_errors}")
    if server_errors:
        raise AssertionError(f"页面请求出现服务端错误：{server_errors}")

    print(json.dumps({
        "baseUrl": BASE_URL,
        "checks": ["三种资格场景", "四条半体制路线", "官网历史岗位详情", "官方公告与附件入口", "控制台无错误"],
        "screenshot": str(screenshot),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
