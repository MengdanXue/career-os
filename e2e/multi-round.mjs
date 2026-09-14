/**
 * 多轮验收：从动态面板实际调 /agent-runs，验证上一轮的上下文真的被用上、也真的被存回去。
 *
 * 复现包里的两个旧反例，这里定为正式回归，断言的是**用户屏幕上的岗位列表**：
 *
 *   1. Agent 追问之后用户只答一句"余杭"。要验的不是"sessionId 回来了"，而是
 *      这一轮的结果真的变了——列表从杭州全量收窄成余杭那几个。
 *   2. 刷新之后用户说"第二个"，指的是**刚展示的那份新列表**的第二个，
 *      不是第一轮那份。会话只读入不写回时，这一条必然指错。
 *
 * 岗位身份从页面上的"查看档案"链接取，即用户真正看到的东西；不看接口内部字段，
 * 也不用列表长度代替身份——长度相同而内容不同的两份列表，长度比不出来。
 *
 * 前置：后端跑在 BASE，规划器按下面的顺序回放六轮输出（每次运行两轮）：
 *   search_jobs location=杭州 / FINISH
 *   search_jobs location=余杭 / FINISH
 *   job_facts ordinal=2      / FINISH
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
const steps = []
function record(name, ok, detail = '') { steps.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`) }

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: ['--no-sandbox'] })
const context = await browser.newContext({ httpCredentials: CRED })
const page = await context.newPage()
const errors = []
page.on('pageerror', e => errors.push(String(e)))
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

/** 打开问答面板和它里面的只读工具编排面板。 */
async function openRunPanel() {
  const launcher = page.getByRole('button', { name: '打开 Career OS 决策助手' })
  if (await launcher.count()) await launcher.click()
  const toggle = page.getByRole('button', { name: /让它自己选工具查/ })
  await toggle.waitFor({ timeout: 20000 })
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click()
  await page.getByLabel('交给它去查').waitFor({ timeout: 15000 })
}

/**
 * 跑一轮，返回页面上渲染出来的岗位 id，按屏幕顺序。
 *
 * 必须等上一轮的结果块先消失、新的再出现。只等"本次消耗预算"这段文字出现是不够的：
 * 它在上一轮就已经在屏幕上，等待会立刻返回，读到的是还没换掉的旧内容，
 * 或者正好读在结果被清掉、新结果还没到的那一瞬间——两种都会让验收给出错误的结论。
 */
async function runRound(question) {
  const result = page.locator('.agent-run-result')
  const box = page.getByLabel('交给它去查')
  await box.fill(question)
  await page.getByRole('button', { name: '运行' }).click()
  await result.waitFor({ state: 'detached', timeout: 15000 }).catch(() => {})
  await result.waitFor({ state: 'attached', timeout: 30000 })
  await page.getByText(/本次消耗预算/).waitFor({ timeout: 30000 })
  const hrefs = await page.locator('.agent-run-jobs a').evaluateAll(
    links => links.map(link => link.getAttribute('href')))
  return hrefs.map(href => href.replace('/opportunities/', ''))
}

await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await openRunPanel()

// --- 第一轮：范围是整个杭州 ---
const hangzhou = await runRound('杭州有哪些岗位')
record('第一轮在页面上列出了岗位', hangzhou.length >= 2, `${hangzhou.length} 个`)
if (hangzhou.length < 2) { console.error('前置条件不满足：第一轮至少要有两个岗位。'); await browser.close(); process.exit(2) }

// --- 反例一：只答"余杭"，结果必须真的变 ---
const yuhang = await runRound('余杭')
record('只答"余杭"之后列表真的变了',
  JSON.stringify(yuhang) !== JSON.stringify(hangzhou),
  `杭州 ${hangzhou.length} 个 → 余杭 ${yuhang.length} 个`)
record('余杭这一轮是杭州那一轮的子集，不是另起一问',
  yuhang.length > 0 && yuhang.every(id => hangzhou.includes(id)))
if (yuhang.length < 2) { console.error('前置条件不满足：余杭这一轮至少要有两个岗位。'); await browser.close(); process.exit(2) }

// --- 反例二：刷新之后"第二个"指向刚展示的那份新列表 ---
await page.reload({ waitUntil: 'networkidle' })
await openRunPanel()
const second = await runRound('第二个怎么样')

record('"第二个"只讲一个岗位', second.length === 1, second.join(','))
record('"第二个"指向刚展示的新列表的第二个',
  second[0] === yuhang[1], `期望 ${yuhang[1]}，实得 ${second[0]}`)
// 会话只读入不写回时，这一轮要么指回第一轮那份列表，要么根本解析不出岗位。
// 所以这条必须同时要求"讲到了一个岗位"——只写不等号会在什么都没有时空过。
record('"第二个"没有指回第一轮那份列表',
  second.length === 1 && second[0] !== hangzhou[1], `第一轮的第二个是 ${hangzhou[1]}`)

record('无 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' / '))

await page.screenshot({ path: process.env.SHOT || '/var/tmp/e2e-multi-round.png', fullPage: true })
await browser.close()

const failed = steps.filter(s => !s.ok)
console.log(`\n=== ${steps.length - failed.length}/${steps.length} 通过 ===`)
process.exit(failed.length ? 1 : 0)
