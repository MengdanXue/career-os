"""Real-browser acceptance for the personal semi-public career workflow."""

from __future__ import annotations

import json
import os
from pathlib import Path

from playwright.sync_api import Page, sync_playwright


BASE_URL = os.environ.get("CAREER_OS_BASE_URL", "http://localhost:8080").rstrip("/")
PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = PROJECT_ROOT / "output" / "playwright"


def screenshot(page: Page, name: str) -> str:
    path = OUTPUT_DIR / name
    page.screenshot(path=path, full_page=True)
    return str(path)


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    console_errors: list[str] = []
    server_errors: list[str] = []
    screenshots: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on("response", lambda response: server_errors.append(f"{response.status} {response.url}") if response.status >= 500 else None)

        page.goto(f"{BASE_URL}/plan", wait_until="networkidle")
        page.get_by_role("heading", name="我的半体制规划", exact=True).wait_for()
        page.get_by_role("heading", name="2027 届境外硕士应届生候选", exact=True).wait_for()
        page.get_by_role("heading", name="应届通道", exact=True).wait_for()
        page.get_by_role("heading", name="社会人员通道", exact=True).wait_for()
        page.get_by_text("境外硕士在读", exact=True).first.wait_for()

        if page.locator(".scenario-rail > li").count() != 3:
            raise AssertionError("规划页没有展示完整的三个学历/认证阶段")
        if page.locator(".route-card").count() < 4:
            raise AssertionError("规划页没有展示完整的四条路线")
        gated_routes = page.locator('.route-card[data-ranking-state="NOT_COVERED"]')
        if gated_routes.count() == 0:
            raise AssertionError("规划页没有把未覆盖路线退出排名")
        for index in range(gated_routes.count()):
            card = gated_routes.nth(index)
            if "暂不排名" not in card.inner_text():
                raise AssertionError("未覆盖路线没有解释暂不排名")
            if card.locator(".decision-index strong").inner_text().strip() == "0":
                raise AssertionError("未覆盖路线被错误展示为 0 分")
        page.get_by_text("登记来源不等于已经采集", exact=True).wait_for()
        screenshots.append(screenshot(page, "career-plan-v3.png"))

        detail_link = None
        evidence_cards = page.locator(".evidence-list article")
        for index in range(evidence_cards.count()):
            card = evidence_cards.nth(index)
            projection = card.locator("p").inner_text()
            if "历史实际：" in projection and "类比：" in projection:
                actual = projection.split("历史实际：", 1)[1].split("·", 1)[0].strip()
                analog = projection.split("类比：", 1)[1].strip()
                if actual != analog:
                    detail_link = card.locator("h3 a")
                    break
        if detail_link is None or not detail_link.is_visible():
            raise AssertionError("规划页没有可下钻且历史实际/2027 类比不同的代表岗位")
        detail_link.click()
        page.wait_for_load_state("networkidle")
        page.get_by_text("历史岗位事实，不代表当前仍可报名", exact=True).wait_for()
        page.get_by_role("heading", name="2027 同类岗位推演", exact=True).wait_for()
        projected = page.locator(".projected-outcome-block article strong").all_inner_texts()
        if len(projected) != 2 or projected[0].split("：", 1)[-1] == projected[1].split("：", 1)[-1]:
            raise AssertionError("岗位详情没有可视化不同的历史实际与 2027 类比结论")
        page.get_by_role("heading", name="能不能报，看这些原始条件", exact=True).wait_for()
        page.get_by_role("heading", name="从公告到聘用，站内查看完整流程", exact=True).wait_for()
        process_text = page.locator(".planning-process").inner_text()
        for required in ("报名", "资格初审", "缴费", "准考证", "笔试", "专业测试", "面试",
                         "体检", "考察", "公示", "聘用", "证据状态"):
            if required not in process_text:
                raise AssertionError(f"岗位详情招聘流程缺少：{required}")
        if page.locator(".planning-official-links a[href]").count() < 2:
            raise AssertionError("岗位详情没有同时保留官方公告与附件入口")
        screenshots.append(screenshot(page, "career-plan-job-detail-v3.png"))

        plan_data = page.request.get(
            f"{BASE_URL}/api/v1/candidates/01992f09-0000-7000-8000-000000000001/career-plan?targetYear=2027"
        ).json()
        representative_ids = {
            item["jobId"]
            for route in plan_data["recommendedRoutes"]
            for item in route["representativeJobs"]
        }
        non_representative = next(
            (item for item in plan_data["jobProjections"] if item["jobId"] not in representative_ids), None
        )
        if non_representative is None:
            raise AssertionError("没有可用于直接下钻验收的非代表岗位")
        direct_job = page.request.get(f"{BASE_URL}/api/v1/jobs/{non_representative['jobId']}").json()
        page.goto(f"{BASE_URL}/jobs/{non_representative['jobId']}", wait_until="networkidle")
        page.get_by_role("heading", name=direct_job["title"], exact=True).wait_for()
        page.get_by_role("heading", name="2027 同类岗位推演", exact=True).wait_for()
        if page.get_by_text("该岗位不在当前规划分析范围内", exact=False).count() > 0:
            raise AssertionError("非代表岗位直接打开后丢失个人资格结论")
        screenshots.append(screenshot(page, "career-plan-non-representative-job-v3.png"))

        page.goto(f"{BASE_URL}/", wait_until="networkidle")
        page.get_by_text("你的机会概览", exact=True).wait_for()
        tier_counts = [int(value) for value in page.locator(".tier-ledger strong").all_inner_texts()]
        if tier_counts and sum(tier_counts[:3]) == 0:
            page.get_by_role("heading", name="确认硕士预计毕业月份", exact=True).wait_for()
            page.get_by_role("heading", name="跟进海外学历认证证据", exact=True).wait_for()
            page.get_by_role("heading", name="建立笔试基础复习计划", exact=True).wait_for()
        screenshots.append(screenshot(page, "career-today-actions-v3.png"))
        browser.close()

    if console_errors:
        raise AssertionError(f"浏览器控制台错误：{console_errors}")
    if server_errors:
        raise AssertionError(f"页面请求出现服务端错误：{server_errors}")

    print(json.dumps({
        "baseUrl": BASE_URL,
        "checks": [
            "2027 境外硕士应届结论", "应届/社会双通道", "境外硕士在读阶段",
            "未覆盖路线无伪 0 分", "三层覆盖说明", "历史实际/2027 类比岗位详情",
            "完整招聘流程与状态", "非代表岗位直接下钻", "官方公告与附件入口",
            "空机会池基础行动", "控制台无错误",
        ],
        "screenshots": screenshots,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
