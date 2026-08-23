"""Browser acceptance for Phase 5B slice 1 personal facts and actions."""

from __future__ import annotations

import json
import os
from pathlib import Path

from playwright.sync_api import Page, sync_playwright


BASE_URL = os.environ.get("CAREER_OS_BASE_URL", "http://localhost:8080")
CANDIDATE_ID = "01992f09-0000-7000-8000-000000000001"
PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = PROJECT_ROOT / "output" / "playwright"


def open_page(page: Page, path: str) -> None:
    page.goto(f"{BASE_URL}{path}", wait_until="networkidle", timeout=60_000)


def assert_personal_home(page: Page) -> None:
    open_page(page, "/")
    page.get_by_text("PERSONAL DOCKET · 今日行动", exact=True).wait_for(timeout=30_000)
    action_count = page.locator(".personal-action").count()
    if action_count > 3:
        raise AssertionError(f"首页个人行动超过三项：{action_count}")
    body = page.locator("body").inner_text()
    if "535 条数据等待你复核" in body or "条数据等待你复核" in body:
        raise AssertionError("首页仍把原始复核队列当作个人行动")
    page.get_by_role("heading", name="你的机会概览").wait_for()
    page.get_by_text("岗位数据管理", exact=True).wait_for()


def assert_profile_evidence(page: Page) -> None:
    open_page(page, "/profile")
    page.get_by_role("heading", name="最影响资格的缺口").wait_for(timeout=30_000)
    employment = page.locator(".profile-evidence-priorities article").filter(
        has_text="补齐可核验工作经历"
    )
    if employment.count() != 1:
        raise AssertionError("画像页没有唯一的工作经历核验任务")
    employment.get_by_role("button", name="处理这项").click()
    target = page.locator("#employment-history :is(input, select, button)").first
    target.wait_for()
    if target.evaluate("element => document.activeElement === element") is not True:
        raise AssertionError("工作经历任务没有定位到对应资料字段")
    body = page.locator("body").inner_text()
    if "旧资料记录 7 年" not in body or "硬资格仍需逐段核验" not in body:
        raise AssertionError("画像页没有区分旧工作年限与已核验经历")
    if "尚未提供" not in body:
        raise AssertionError("画像页没有把空且未知的事实表达为尚未提供")


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    console_errors: list[str] = []
    server_errors: list[str] = []
    desktop = OUTPUT_DIR / "phase5b-today-desktop.png"
    profile = OUTPUT_DIR / "phase5b-profile-evidence.png"
    mobile = OUTPUT_DIR / "phase5b-today-mobile.png"

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        context.add_init_script(
            f"localStorage.setItem('career-os.selected-candidate', '{CANDIDATE_ID}')"
        )
        page = context.new_page()
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on("response", lambda response: server_errors.append(f"{response.status} {response.url}") if response.status >= 500 else None)

        assert_personal_home(page)
        page.screenshot(path=desktop, full_page=True)
        assert_profile_evidence(page)
        page.screenshot(path=profile, full_page=True)

        page.set_viewport_size({"width": 390, "height": 844})
        assert_personal_home(page)
        page.screenshot(path=mobile, full_page=True)
        browser.close()

    if console_errors:
        raise AssertionError(f"浏览器控制台错误：{console_errors}")
    if server_errors:
        raise AssertionError(f"页面请求出现服务端错误：{server_errors}")

    print(json.dumps({
        "baseUrl": BASE_URL,
        "checks": [
            "首页最多三项个人行动",
            "首页不暴露原始复核数量",
            "画像工作经历任务定位",
            "旧年限与核验事实分离",
            "桌面与 390px 手机布局",
            "浏览器控制台无错误",
        ],
        "screenshots": [str(desktop), str(profile), str(mobile)],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
