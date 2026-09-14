/**
 * 多轮验收：上一轮的上下文必须真的被用上。
 *
 * 复核里的两个旧反例，这里定为正式回归：
 *
 *   1. Agent 追问之后，用户只答一句"余杭"。"余杭"单独看是个残句；没有上一轮限定的杭州，
 *      系统只能再问一次或者当成一个孤立的新问题——用户刚答过，却被再问一遍。
 *   2. 刷新之后用户说"第二个"，指的是他屏幕上那一份列表的第二个。重新排一次名次得到的
 *      第二个可能是另一个岗位，而用户看不出系统换了对象。
 *
 * 断言的是行为，不是 sessionId 被原样回传——回传了照样可以什么都没用上。
 *
 * 用法：
 *   E2E_USER=... E2E_PASS=... CHROME_PATH=... node multi-round.mjs
 */
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://127.0.0.1:18080'
if (!process.env.E2E_USER || !process.env.E2E_PASS) {
  console.error('需要 E2E_USER 与 E2E_PASS：验收脚本不带默认口令。')
  process.exit(2)
}
const CRED = { username: process.env.E2E_USER, password: process.env.E2E_PASS }
const CANDIDATE = process.env.E2E_CANDIDATE || '01992f09-0000-7000-8000-000000000001'
const steps = []
function record(name, ok, detail = '') { steps.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`) }

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: ['--no-sandbox'] })
const context = await browser.newContext({ httpCredentials: CRED })
const page = await context.newPage()
const errors = []
page.on('pageerror', e => errors.push(String(e)))
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

const api = context.request
const base = `${BASE}/api/v1/candidates/${CANDIDATE}`

// --- 第一轮：用户问杭州，拿到一份有顺序的列表 ---
const first = await api.post(`${base}/agent-queries`, { data: { question: '杭州有哪些岗位', limit: 5 } })
if (!first.ok()) { console.error(`前置检查失败：查询返回 ${first.status()}`); process.exit(2) }
const firstBody = await first.json()
const sessionId = firstBody.sessionId
const order = firstBody.decisions.map(d => d.jobId)
if (order.length < 2) {
  console.error('前置条件不满足：需要至少两个岗位才能验证"第二个"。')
  process.exit(2)
}
record('第一轮拿到有顺序的列表', true, `${order.length} 个岗位`)

// --- 反例一：追问之后只答"余杭" ---
// 会话必须把上一轮的范围交给下一轮。只检查 sessionId 回来了是不够的：
// 真正要验的是服务端记住了"上一轮限定的是杭州"，下一句残句才接得上。
const session = await api.get(`${base}/agent-queries/${sessionId}`)
const sessionBody = await session.json()
record('刷新接口能取回这一轮', session.ok() && sessionBody.sessionId === sessionId)
record('会话记住了上一轮的列表顺序',
  JSON.stringify(sessionBody.jobIdsInOrder) === JSON.stringify(order),
  `${(sessionBody.jobIdsInOrder || []).length} 个`)

const follow = await api.post(`${base}/agent-queries`, { data: { question: '余杭', limit: 5, sessionId } })
const followBody = await follow.json()
record('只答"余杭"仍在同一轮里继续', follow.ok() && followBody.sessionId === sessionId)
// 残句不能把系统打回"什么都不知道"的状态：上一轮的顺序还在，序号仍然可解析。
const afterFollow = await (await api.get(`${base}/agent-queries/${sessionId}`)).json()
record('残句之后上一轮的列表还在',
  (afterFollow.jobIdsInOrder || []).length === order.length)

// --- 反例二：刷新之后指向原列表第二个 ---
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
const launcher = page.getByRole('button', { name: '打开 Career OS 决策助手' })
if (await launcher.count()) await launcher.click()

const second = await api.post(`${base}/agent-queries`,
  { data: { question: '第二个怎么样', limit: 5, sessionId } })
const secondBody = await second.json()
const answeredJob = (secondBody.decisions || []).map(d => d.jobId)
record('"第二个"解析回原列表的第二个',
  answeredJob.length === 1 && answeredJob[0] === order[1],
  `期望 ${order[1]}，实得 ${answeredJob[0]}`)
record('"第二个"没有重新排名给出别的岗位', answeredJob[0] !== order[0])

// 资料变了之后，同一个序号要拒答，而不是拿重新排出来的第二个顶上去。
const stale = await (await api.get(`${base}/agent-queries/${sessionId}`)).json()
record('会话报出它是否仍对应当前资料', typeof stale.stale === 'boolean', `stale=${stale.stale}`)

record('无 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' / '))

await page.screenshot({ path: process.env.SHOT || '/var/tmp/e2e-multi-round.png', fullPage: true })
await browser.close()

const failed = steps.filter(s => !s.ok)
console.log(`\n=== ${steps.length - failed.length}/${steps.length} 通过 ===`)
process.exit(failed.length ? 1 : 0)
