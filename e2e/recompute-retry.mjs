/**
 * 重算失败之后，用户要能在页面上把它接着做完。
 *
 * 这条路径单元测试测不到：服务层的假重算器只是抛异常，没有事务、没有台账、没有页面。
 * 真机上它曾经把用户的回答连同台账一起回滚掉（HTTP 500），修好之后仍然只在页面上写了一句
 * "稍后重试即可"——没有按钮，这条恢复路径对用户等于不存在。所以这里要用真实浏览器点它。
 *
 * 失败注入点做成参数：这个脚本不该知道怎么弄坏一个岗位，只知道"弄坏之后应该发生什么"。
 *
 * 用法：
 *   E2E_USER=... E2E_PASS=... CHROME_PATH=... \
 *   E2E_BREAK_CMD='psql ... -c "…"' E2E_REPAIR_CMD='psql ... -c "…"' \
 *   node recompute-retry.mjs
 */
import { execSync } from 'node:child_process'
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://127.0.0.1:18080'
if (!process.env.E2E_USER || !process.env.E2E_PASS) {
  console.error('需要 E2E_USER 与 E2E_PASS：验收脚本不带默认口令。')
  process.exit(2)
}
if (!process.env.E2E_BREAK_CMD || !process.env.E2E_REPAIR_CMD) {
  console.error('需要 E2E_BREAK_CMD 与 E2E_REPAIR_CMD：没有真实的失败注入，这个脚本证明不了任何事。')
  process.exit(2)
}
const CRED = { username: process.env.E2E_USER, password: process.env.E2E_PASS }
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

const probe = await context.request.post(`${BASE}/api/v1/candidates/${CANDIDATE}/agent-queries`,
  { data: { question: QUESTION, limit: 5 } })
if (!probe.ok()) { console.error(`前置检查失败：查询返回 ${probe.status()}`); process.exit(2) }
const probeBody = await probe.json()
if ((probeBody.pendingConfirmations ?? []).length < 1) {
  console.error('前置条件不满足：需要至少一条待确认事项。')
  process.exit(2)
}

await page.goto(BASE + '/', { waitUntil: 'networkidle' })
const launcher = page.getByRole('button', { name: '打开 Career OS 决策助手' })
if (await launcher.count()) await launcher.click()
const box = page.getByLabel('向 Career OS 提问')
await box.waitFor({ timeout: 15000 })
await box.fill(QUESTION)
await page.getByRole('button', { name: '分析' }).click()
await page.getByText('这些还要你确认').waitFor({ timeout: 30000 })
record('渲染待确认问题', true)

// 现在把重算弄坏。写入那一段不受影响——这正是两段式要保住的东西。
execSync(process.env.E2E_BREAK_CMD, { stdio: 'ignore' })
record('已注入重算失败', true)

await page.getByRole('button', { name: '中共党员' }).click()
const retry = page.getByRole('button', { name: '继续重算' })
await retry.waitFor({ timeout: 30000 }).catch(() => {})
record('重算失败时页面给出按钮，而不是让用户"稍后重试"', await retry.count() > 0)

// 回答必须还在：重算失败不该把用户的声明一起丢掉。
const afterBreak = await page.locator('.pending-confirmations').innerText()
record('回答没有因为重算失败而丢失',
  afterBreak.includes('已经记下了') || afterBreak.includes('本人的声明'),
  afterBreak.split('\n').find(line => line.includes('记')) ?? '')
record('没有 5xx 打到用户脸上', !afterBreak.includes('没有完成这次请求'))

execSync(process.env.E2E_REPAIR_CMD, { stdio: 'ignore' })
await retry.click()
await page.waitForTimeout(4000)
const afterRetry = await page.locator('.pending-confirmations').innerText()
record('原键重算把结论补上了', !afterRetry.includes('还没重算完'),
  afterRetry.split('\n').slice(0, 3).join(' / '))
// 同一把幂等钥匙重放不会被当成换了答案，也不会撞上版本检查。
record('重放没有被当成重复写入或版本冲突',
  !afterRetry.includes('已经用于记录') && !afterRetry.includes('资料在你回答期间已变更'))

record('无 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' / '))

await page.screenshot({ path: process.env.SHOT || '/var/tmp/e2e-retry.png', fullPage: true })
await browser.close()

const failed = steps.filter(s => !s.ok)
console.log(`\n=== ${steps.length - failed.length}/${steps.length} 通过 ===`)
process.exit(failed.length ? 1 : 0)
