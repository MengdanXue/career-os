"""Browser acceptance for Hangzhou lifecycle source health."""

from __future__ import annotations

import json
from pathlib import Path

from playwright.sync_api import sync_playwright


BASE_URL = "http://localhost:8080"
OUTPUT = Path(__file__).resolve().parents[1] / "output" / "playwright" / "hangzhou-lifecycle-source-health.png"


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    console_errors: list[str] = []
    server_errors: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on("response", lambda response: server_errors.append(f"{response.status} {response.url}") if response.status >= 500 else None)
        page.goto(f"{BASE_URL}/updates", wait_until="networkidle", timeout=60_000)

        source = page.locator(".source-row").filter(has_text="杭州市西湖区政府招聘")
        if source.count() != 1:
            raise AssertionError(f"西湖区来源行数量不正确：{source.count()}")
        source.get_by_text("官网访问正常", exact=False).wait_for()
        source.get_by_text("后续公告 0 · 已关联 0 · 待关联 0 · 歧义 0", exact=True).wait_for()
        source.get_by_text("历史附件问题记录", exact=False).wait_for()
        source.get_by_role("link", name="查看官方来源").wait_for()
        page.screenshot(path=OUTPUT, full_page=True)
        browser.close()

    if console_errors:
        raise AssertionError(f"浏览器控制台错误：{console_errors}")
    if server_errors:
        raise AssertionError(f"页面请求出现服务端错误：{server_errors}")
    print(json.dumps({
        "checks": ["西湖官方来源可见", "生命周期零值明确展示", "历史附件问题不冒充当前失败", "官方链接保留", "无控制台或服务端错误"],
        "screenshot": str(OUTPUT),
        "status": "PASS",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
