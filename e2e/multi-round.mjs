/**
 * 多轮验收，两段。
 *
 * 第一段——搜索零结果 → 追问 → 刷新 → 短回答继续：
 *   用户问"宁波有什么合适的"，系统真的查了、一个岗位都没查到，于是反问"要不要放宽范围"。
 *   这一轮**既有新范围**（宁波，空的），**也仍然欠着那件事**。把"存新列表"和"存待答任务"
 *   写成二选一，这一轮就只存前者——刷新之后屏幕上那句追问没了，他答一句"杭州"，
 *   系统不知道这是在回答什么。所以这一段验的是：零结果那一轮追问过之后，刷新仍然摆得回来，
 *   短回答仍然接得上原来那件事。
 *
 * 第二段——追问 → 刷新 → 只答一句"余杭" → 接着办原来那件事 → 再问"第二个"：
 *   1. 系统问出一句话，用户还没来得及答就刷新了。刷新之后页面要摆回"上次问你什么"和"为了办什么"。
 *   2. 只答一句"余杭"，系统要接着办原来那件事，给出一份真的收窄过的新列表。
 *   3. 再问"第二个"，指的是**刚展示的那份新列表**的第二个。会话只读入不写回时必然指错。
 *
 * 全程从页面上的动态面板实际调 /agent-runs，断言的是用户屏幕上的东西。
 * 岗位身份取自页面上"查看档案"链接里的 id，不看接口内部字段，也不用列表长度代替身份——
 * 长度相同而内容不同的两份列表，长度比不出来。
 *
 * 前置：后端跑在 BASE，规划器按下面的顺序回放九轮输出（见 /var/tmp/mrcfg/application.yml）：
 *   TOOL search_jobs location=宁波 ; ASK    BROADEN_SCOPE  / BASIS 1
 *   TOOL search_jobs location=杭州 ; FINISH RANKED_LISTING / BASIS 1
 *   ASK WHICH_LOCATION / BASIS none
 *   TOOL search_jobs location=余杭 ; FINISH RANKED_LISTING / BASIS 1
 *   TOOL job_facts ordinal=2      ; FINISH SINGLE_JOB     / BASIS 1
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
 * 跑一轮，返回页面上渲染出来的岗位 id（按屏幕顺序）和这一轮的收尾／追问文字。
 *
 * 必须等上一轮的结果块先消失、新的再出现。只等某段文字出现是不够的：它上一轮就在屏幕上，
 * 等待会立刻返回，读到的是正在被替换的旧内容。
 */
async function runRound(question) {
  const result = page.locator('.agent-run-result')
  await page.getByLabel('交给它去查').fill(question)
  await page.getByRole('button', { name: '运行' }).click()
  await result.waitFor({ state: 'detached', timeout: 15000 }).catch(() => {})
  await result.waitFor({ state: 'attached', timeout: 30000 })
  await page.getByText(/本次消耗预算/).waitFor({ timeout: 30000 })
  const hrefs = await page.locator('.agent-run-jobs a').evaluateAll(
    links => links.map(link => link.getAttribute('href')))
  return {
    jobs: hrefs.map(href => href.replace('/opportunities/', '')),
    text: await result.innerText(),
    // 轨迹在折叠的 <details> 里，innerText 读不到；要看"这一轮到底查了什么"只能取 textContent。
    trace: await page.locator('.agent-run-trace').first()
      .evaluate(node => node.textContent || '').catch(() => ''),
  }
}

/** 刷新，并把助手面板重新打开。 */
async function reloadAndReopen() {
  await page.reload({ waitUntil: 'networkidle' })
  const launcher = page.getByRole('button', { name: '打开 Career OS 决策助手' })
  if (await launcher.count()) await launcher.click()
}

await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await openRunPanel()

// ===== 第一段：搜索零结果 → 追问 → 刷新 → 短回答继续 =====

// 系统真的查了宁波，一个都没查到，于是反问。这一轮既有新范围，也仍然欠着那件事。
const empty = await runRound('宁波有什么合适的')
record('零结果那一轮以追问收场', empty.text.includes('转向追问'), empty.text.split('\n')[0])
record('追问里说明了这个范围内没有找到岗位',
  empty.text.includes('没有找到岗位') && empty.text.includes('放宽'),
  (empty.text.match(/这个范围内[^\n]*/) || [''])[0])
record('零结果那一轮没有列岗位', empty.jobs.length === 0, `${empty.jobs.length} 个`)
record('这一轮确实查过（轨迹里有宁波那次查询）', empty.trace.includes('location=宁波'),
  (empty.trace.match(/location=[^\s]*/) || [''])[0])

// 刷新：查过不等于那件事办完了，追问和原来那件事都要还在。
await reloadAndReopen()
const emptyRestored = page.getByText(/上次问你：/)
await emptyRestored.waitFor({ timeout: 20000 }).catch(() => {})
record('查过之后刷新，那句追问仍然摆得回来', await emptyRestored.count() > 0,
  await emptyRestored.count() ? (await emptyRestored.first().innerText()).replace('\n', ' ') : '没有这条')
record('刷新后也说明了这是在办哪件事（宁波那件）',
  await page.getByText(/为了：宁波有什么合适的/).count() > 0)

// 只答一句"杭州"：接着办原来那件事，给出真的放宽过的新列表。
await openRunPanel()
const broadened = await runRound('杭州')
record('短回答"杭州"之后接上了原来那件事', broadened.jobs.length >= 1, `${broadened.jobs.length} 个`)
record('放宽之后不再说"没有找到岗位"', !broadened.text.includes('没有找到岗位'))
if (broadened.jobs.length < 1) { console.error('前置条件不满足：杭州这一轮至少要有一个岗位。'); await browser.close(); process.exit(2) }

// 那件事办完了，待答状态要清掉——不清的话刷新之后还会摆着一句早就答过的追问。
await reloadAndReopen()
record('办完之后刷新不再摆着那句追问', await page.getByText(/上次问你：/).count() === 0)
await openRunPanel()

// ===== 第二段：追问 → 刷新 → 只答余杭 → 再问第二个 =====

// --- 第一轮：系统问出一句话，用户还没答 ---
const asked = await runRound('有什么合适的')
record('第一轮以追问收场', asked.text.includes('转向追问'), asked.text.split('\n')[0])
record('追问是程序渲染的固定句式', asked.text.includes('哪个城市或区县'),
  (asked.text.match(/你想看[^\n]*/) || [''])[0])
record('追问这一轮没有列岗位', asked.jobs.length === 0)

// --- 刷新：那句追问和原来那件事都要摆回来 ---
await reloadAndReopen()
const restoredQuestion = page.getByText(/上次问你：/)
await restoredQuestion.waitFor({ timeout: 20000 }).catch(() => {})
record('刷新后摆回了那句追问', await restoredQuestion.count() > 0,
  await restoredQuestion.count() ? (await restoredQuestion.first().innerText()).replace('\n', ' ') : '没有这条')
record('刷新后也说明了这是在办哪件事',
  await page.getByText(/为了：有什么合适的/).count() > 0)

// --- 只答一句"余杭"：接着办原来那件事，给出真的收窄过的新列表 ---
await openRunPanel()
const yuhang = await runRound('余杭')
record('只答"余杭"之后拿到了新列表', yuhang.jobs.length >= 2, `${yuhang.jobs.length} 个`)
if (yuhang.jobs.length < 2) { console.error('前置条件不满足：余杭这一轮至少要有两个岗位。'); await browser.close(); process.exit(2) }

// --- 再问"第二个"：指的是刚展示的那份新列表 ---
const second = await runRound('第二个怎么样')
record('"第二个"只讲一个岗位', second.jobs.length === 1, second.jobs.join(','))
record('"第二个"指向刚展示的新列表的第二个',
  second.jobs[0] === yuhang.jobs[1], `期望 ${yuhang.jobs[1]}，实得 ${second.jobs[0]}`)
// 会话只读入不写回时，这一轮要么指错，要么根本解析不出岗位。
record('"第二个"不是新列表的第一个',
  second.jobs.length === 1 && second.jobs[0] !== yuhang.jobs[0], `新列表第一个是 ${yuhang.jobs[0]}`)

record('无 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' / '))

await page.screenshot({ path: process.env.SHOT || '/var/tmp/e2e-multi-round.png', fullPage: true })
await browser.close()

const failed = steps.filter(s => !s.ok)
console.log(`\n=== ${steps.length - failed.length}/${steps.length} 通过 ===`)
process.exit(failed.length ? 1 : 0)
