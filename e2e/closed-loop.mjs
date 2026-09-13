/**
 * 真实浏览器 + 真实后端 + 真实数据库的闭环验收。
 *
 * 这些检查在单元测试里全是绿的，连起来才暴露问题——连续确认曾经必定在第二步失败，
 * 关注清单曾经根本没有入口。所以这一份必须打真实的栈，不能用替身。
 *
 * 前置：后端跑在 BASE（默认 127.0.0.1:18080），已构建前端到它的 static 目录；
 * 数据库里有一个候选人、至少一个 VERIFIED/INCLUDED 的岗位，且该候选人尚有待确认事项。
 *
 * 用法：
 *   mkdir -p /tmp/e2e && cd /tmp/e2e && npm i playwright
 *   cp <repo>/e2e/closed-loop.mjs . && CHROME_PATH=<chrome> node closed-loop.mjs
 */
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://127.0.0.1:18080'
const CRED = { username: process.env.E2E_USER || 'e2e', password: process.env.E2E_PASS || 'e2e-local-pass' }
const CANDIDATE = process.env.E2E_CANDIDATE || '01992f09-0000-7000-8000-000000000001'
const QUESTION = process.env.E2E_QUESTION || '杭州有哪些岗位'
const steps = []
function record(name, ok, detail = '') { steps.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`) }

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: ['--no-sandbox'] })
const context = await browser.newContext({ httpCredentials: CRED })
const page = await context.newPage()
const errors = []
page.on('pageerror', e => errors.push(String(e)))
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

// 前置条件先查清楚再跑。脚本依赖"还有待确认事项、该岗位尚未关注"这个起点；
// 起点不对时，失败看起来和产品缺陷一模一样——这一点已经浪费过一次排查。
const probe = await context.request.post(
  `${BASE}/api/v1/candidates/${CANDIDATE}/agent-queries`,
  { data: { question: QUESTION, limit: 5 } })
if (!probe.ok()) { console.error(`前置检查失败：查询返回 ${probe.status()}`); process.exit(2) }
const probeBody = await probe.json()
if ((probeBody.pendingConfirmations ?? []).length < 2) {
  console.error('前置条件不满足：需要至少两条待确认事项才能验证连续确认。')
  console.error('当前待确认：' + JSON.stringify((probeBody.pendingConfirmations ?? []).map(p => p.factKey)))
  console.error('请把候选人的 GENDER / POLITICAL_AFFILIATION 置为 UNCONFIRMED 后重跑。')
  process.exit(2)
}

await page.goto(BASE + '/', { waitUntil: 'networkidle' })
record('页面加载', await page.title() !== '', 'title=' + await page.title())

// 打开问答面板
const launcher = page.getByRole('button', { name: '打开 Career OS 决策助手' })
if (await launcher.count()) await launcher.click()

const box = page.getByLabel('向 Career OS 提问')
await box.waitFor({ timeout: 15000 })
await box.fill(QUESTION)
await page.getByRole('button', { name: '分析' }).click()

// 等待确定性事实块出现
await page.getByText('机会决策指数').first().waitFor({ timeout: 30000 })
const answer = await page.locator('.answer-text').first().innerText()
record('答案含确定性事实块', answer.includes('硬资格') && answer.includes('不是录取概率'))
record('答案未把指数说成录取概率', !/录取概率(?!，|。|$)/.test(answer.replace('不是录取概率', '')))

// 待确认问题渲染出来了
const pendingHeading = page.getByText('这些还要你确认')
record('渲染待确认问题', await pendingHeading.count() > 0)
record('先声明这是本人声明', await page.getByText(/不会把它当作官方核实/).count() > 0)

// 连续确认：答第一个
const first = page.getByRole('button', { name: '中共党员' })
if (await first.count()) {
  await first.click()
  await page.getByText(/本人的声明|已经是这个答案|已记录/).first().waitFor({ timeout: 30000 })
  record('确认 #1 已记录', true)
} else { record('确认 #1 已记录', false, '没有找到政治面貌选项') }

// 连续确认：答第二个（不重新查询）
const second = page.getByRole('button', { name: '女' })
if (await second.count()) {
  await second.click()
  await page.waitForTimeout(2500)
  const text = await page.locator('.pending-confirmations').innerText()
  const blocked = text.includes('资料在你回答期间已变更')
  record('确认 #2 未被版本检查挡下', !blocked, blocked ? '仍报版本变更' : '')
} else { record('确认 #2 未被版本检查挡下', false, '没有找到性别选项') }

// 关注入口
const watch = page.getByRole('button', { name: '关注' }).first()
if (await watch.count()) {
  await watch.click()
  await page.getByText('已加入关注').first().waitFor({ timeout: 15000 })
  record('可以从结果行加入关注', true)
} else { record('可以从结果行加入关注', false, '结果行没有关注按钮') }

// 刷新恢复：重新载入后关注清单还在
await page.reload({ waitUntil: 'networkidle' })
await page.getByText('WATCHLIST · 关注清单').waitFor({ timeout: 20000 })
record('刷新后关注清单可见', true)

record('无 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' / '))

await page.screenshot({ path: process.env.SHOT || '/var/tmp/e2e.png', fullPage: true })
await browser.close()

const failed = steps.filter(s => !s.ok)
console.log(`\n=== ${steps.length - failed.length}/${steps.length} 通过 ===`)
process.exit(failed.length ? 1 : 0)
