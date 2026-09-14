/** Real browser -> real HTTP backend -> guarded synthetic PostgreSQL. No mocks or data resets. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { loadIsolatedFixture } from './fixture.mjs';

const { chromium } = process.env.E2E_PLAYWRIGHT_MODULE
  ? await import(pathToFileURL(path.resolve(process.env.E2E_PLAYWRIGHT_MODULE)).href) : await import('playwright');
const fixture = loadIsolatedFixture(process.env.CAREER_OS_E2E_FIXTURE_MANIFEST);
const m = fixture.manifest;
assert(process.env.E2E_USER && process.env.E2E_PASS, 'Explicit random test credentials required');
const output = path.join(path.dirname(fixture.manifestPath), 'browser-evidence');
mkdirSync(output, { recursive: true });
const report = { startedAt: new Date().toISOString(), runId: m.runId,
  codeSha: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8', windowsHide: true }).trim(),
  worktreeChanges: execFileSync('git', ['status', '--porcelain'], { encoding: 'utf8', windowsHide: true }).trim(),
  backendJarSha256: createHash('sha256').update(readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../career-web/target/career-web-0.1.0-SNAPSHOT.jar'))).digest('hex'),
  baseUrl: m.baseUrl, candidateId: m.candidateId, steps: [], requests: [], responses: [], apiTraffic: [], errors: [] };
function pass(name, detail = {}) { report.steps.push({ name, ok: true, detail }); console.log(`PASS | ${name}`); }
function snapshot(name) {
  const value = fixture.snapshot();
  writeFileSync(path.join(output, `${name}.json`), JSON.stringify(value, null, 2));
  return value;
}
let browser, context, page;
try {
  const before = snapshot('00-before');
  assert.equal(before.faultActive, false);
  assert.equal(before.flywayVersion, 88);
  assert.equal(before.ledger.length, 0, 'Fresh fixture required; never reset or reuse a completed run');
  assert.equal(before.sessions.length, 0);
  assert.equal(before.watches.length, 0);
  assert.deepEqual(before.jobs.map(j => j.id), [m.firstJobId, m.secondJobId]);
  assert.equal(before.candidate.gender, 'UNKNOWN');
  assert.equal(before.candidate.political_affiliation, 'UNKNOWN');
  pass('隔离起点：新候选人、两个合成岗位、零会话/确认/关注、原候选人未改');
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, headless: true });
  context = await browser.newContext({ httpCredentials: { username: process.env.E2E_USER, password: process.env.E2E_PASS }, viewport: { width: 1440, height: 1080 } });
  await context.addInitScript(candidate => localStorage.setItem('career-os.selected-candidate', candidate), m.candidateId);
  page = await context.newPage();
} catch (error) {
  report.outcome = 'FAIL';
  report.failure = String(error.stack ?? error);
  report.finishedAt = new Date().toISOString();
  writeFileSync(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  await browser?.close();
  throw error;
}
page.setDefaultTimeout(25_000);
page.on('pageerror', error => report.errors.push(String(error)));
page.on('request', request => {
  if (request.url().includes('/api/')) report.apiTraffic.push({ method: request.method(), url: request.url() });
  if (request.url().includes('/api/') && ['POST', 'PUT', 'DELETE'].includes(request.method()))
    report.requests.push({ method: request.method(), url: request.url(), body: request.postDataJSON() });
});
let sessionId;
const pendingKeys = ['EMPLOYER_SETTLEMENT_AT_APPLICATION', 'GENDER', 'POLITICAL_AFFILIATION', 'SOCIAL_INSURANCE_AT_APPLICATION'];
const factRow = fact => page.locator(`.pending-confirmations article[data-fact-key="${fact}"]`);
const decisionRows = () => page.locator('.agent-answer > ul > li[data-decision-id]');
async function business(action, suffix, method = 'POST') {
  const awaiting = page.waitForResponse(response => response.request().method() === method && new URL(response.url()).pathname.endsWith(suffix));
  await action();
  const response = await awaiting;
  const body = await response.json();
  assert.equal(response.status(), 200, JSON.stringify(body));
  report.responses.push({ method, url: response.url(), status: response.status(), body });
  return { body, request: response.request().postDataJSON(), url: response.url() };
}
async function question(text) {
  await page.getByLabel('向 Career OS 提问').fill(text);
  const result = await business(() => page.getByRole('button', { name: '分析', exact: true }).click(), '/agent-queries');
  assert.equal(result.body.modelPhrased, false);
  assert.equal(result.body.fallbackUsed, false);
  assert(result.body.answer.includes('不是录取概率'));
  await page.locator(`[data-session-id="${result.body.sessionId}"]`).waitFor();
  await page.getByTestId('remaining-confirmations').waitFor();
  await page.waitForFunction(({ version, ids }) => {
    const displayed = document.querySelector('[data-testid="display-profile-version"]')?.textContent;
    const actual = [...document.querySelectorAll('.agent-answer > ul > li[data-decision-id]')].map(row => row.getAttribute('data-job-id'));
    return displayed === version && JSON.stringify(actual) === JSON.stringify(ids);
  }, { version: result.body.profileVersion, ids: result.body.decisions.map(d => d.jobId) });
  return result.body;
}
async function session(expectedPending, version) {
  await page.getByTestId('remaining-confirmations').filter({ hasText: String(expectedPending.length) }).waitFor();
  if (version) await page.getByTestId('session-profile-version').filter({ hasText: version }).waitFor();
  const response = await context.request.get(`${m.baseUrl}/api/v1/candidates/${m.candidateId}/agent-queries/${sessionId}`);
  assert.equal(response.status(), 200);
  const result = await response.json();
  assert.equal(result.sessionId, sessionId);
  assert.equal(result.stale, false);
  assert.deepEqual(result.jobIdsInOrder, [m.firstJobId, m.secondJobId]);
  assert.deepEqual(result.pendingConfirmations.filter(item => !item.answered).map(item => item.factKey).sort(), [...expectedPending].sort());
  if (version) { assert.equal(result.profileVersion, version); assert.equal(result.currentProfileVersion, version); }
  return result;
}
function confirmDb(name, response, factKey, value, expectedCount, expectedStage = 'RECOMPUTED') {
  const s = snapshot(name);
  assert.equal(response.body.evidenceStrength, 'SELF_REPORTED');
  assert.equal(s.ledger.length, expectedCount);
  const ledger = s.ledger.find(row => row.idempotency_key === response.request.idempotencyKey);
  assert(ledger, 'Actual confirmation ledger row missing');
  assert.equal(ledger.fact_key, factKey);
  assert.equal(ledger.declared_value, value);
  assert.equal(ledger.stage, expectedStage);
  assert.equal(s.candidate[factKey.toLowerCase()], value);
  assert.equal(s.candidate.profile_version, response.body.profileVersionAfter);
  assert.notEqual(response.body.profileVersionBefore, response.body.profileVersionAfter);
  const fact = s.facts.find(row => row.fact_key === factKey);
  assert.equal(fact.status, 'CONFIRMED');
  assert.equal(fact.source, 'USER_CONFIRMED');
  if (expectedStage === 'RECOMPUTED') {
    assert.equal(response.body.result, 'RECORDED');
    assert.equal(response.body.changes?.available, true, 'Recompute result must be positively available');
    assert.equal(response.body.changes.currentProfileVersion, response.body.profileVersionAfter);
    const decision = s.decisions.find(row => row.job_posting_id === m.secondJobId && row.profile_version === response.body.profileVersionAfter);
    assert(decision, 'Second job actual recomputed decision missing');
    const eligibility = s.eligibilityAssessments.find(row => row.id === decision.eligibility_assessment_id);
    assert(eligibility, 'Decision must reference an actual persisted eligibility assessment');
    assert.equal(eligibility.profile_version, response.body.profileVersionAfter);
    assert.equal(eligibility.job_posting_id, m.secondJobId);
    assert.equal(eligibility.status, decision.eligibility_status);
    if (factKey === 'GENDER' || factKey === 'POLITICAL_AFFILIATION')
      assert.equal(eligibility.rule_results[factKey].status, value === 'NON_MEMBER' ? 'INELIGIBLE' : 'ELIGIBLE');
    if (factKey === 'SOCIAL_INSURANCE_AT_APPLICATION') {
      assert.equal(eligibility.rule_results.FRESH_GRADUATE_STATUS.status, 'CONDITIONAL');
      assert.equal(decision.eligibility_status, 'CONDITIONAL');
    }
  }
  return s;
}
async function answer(factKey, label) {
  return business(() => factRow(factKey).getByRole('button', { name: label, exact: true }).click(), '/profile-confirmations');
}
async function watchRead() {
  const response = await context.request.get(`${m.baseUrl}/api/v1/candidates/${m.candidateId}/watched-jobs?asOf=${m.asOf}`);
  assert.equal(response.status(), 200);
  return response.json();
}
async function exactRestoration(expectedPending, version, name) {
  const writes = report.requests.length;
  await page.reload({ waitUntil: 'networkidle' });
  const restored = await session(expectedPending, version);
  assert.equal(await page.locator('.agent-session-state').getAttribute('data-session-id'), sessionId);
  assert.deepEqual(await decisionRows().evaluateAll(rows => rows.map(row => row.dataset.jobId)), [m.secondJobId]);
  assert.equal(report.requests.length, writes, 'Reload must not automatically repeat business writes or ranking queries');
  const s = snapshot(name);
  assert.equal(s.sessions.length, 1);
  assert.equal(s.sessions[0].id, sessionId);
  assert.deepEqual(s.sessions[0].last_job_ids, [m.firstJobId, m.secondJobId]);
  pass('刷新恢复原会话、剩余适用问题与原第二岗位，未自动写入或重排', { name, session: restored });
}

try {
  await page.goto(m.baseUrl, { waitUntil: 'networkidle' });
  await page.getByText('还没有关注任何岗位', { exact: true }).waitFor();
  await page.getByRole('button', { name: '打开 Career OS 决策助手' }).click();
  const listing = await question('杭州有哪些岗位');
  sessionId = listing.sessionId;
  assert.deepEqual(listing.decisions.map(d => d.jobId), [m.firstJobId, m.secondJobId]);
  assert.deepEqual(listing.pendingConfirmations.map(item => item.factKey).sort(), pendingKeys);
  pass('列表来自确定性事实，第二岗位置明确，四个适用待确认项', { sessionId, jobIds: listing.decisions.map(d => d.jobId) });
  const focused = await question('第二个岗位怎么样');
  assert.equal(focused.sessionId, sessionId);
  assert.deepEqual(focused.decisions.map(d => d.jobId), [m.secondJobId]);
  assert.deepEqual(focused.pendingConfirmations.map(item => item.factKey).sort(), pendingKeys);
  assert(focused.pendingConfirmations.every(item => item.jobPostingId === m.secondJobId));
  await session(pendingKeys, focused.profileVersion);
  pass('问第二个：原序号指向同一岗位并返回四个适用问题');
  const political = await answer('POLITICAL_AFFILIATION', '中共党员');
  confirmDb('01-political', political, 'POLITICAL_AFFILIATION', 'CPC_MEMBER', 1);
  await session(pendingKeys.filter(k => k !== 'POLITICAL_AFFILIATION'), political.body.profileVersionAfter);
  pass('确认1：RECORDED、本人声明落库、实际重算、问题及版本已刷新');
  const gender = await answer('GENDER', '女');
  confirmDb('02-gender', gender, 'GENDER', 'FEMALE', 2);
  assert.notEqual(gender.request.idempotencyKey, political.request.idempotencyKey);
  await session(['EMPLOYER_SETTLEMENT_AT_APPLICATION', 'SOCIAL_INSURANCE_AT_APPLICATION'], gender.body.profileVersionAfter);
  pass('确认2：正向业务成功、FEMALE真实落库和重算，不是仅断言未报错');

  fixture.setRecomputeFailure(true);
  let deferred;
  try {
    deferred = await answer('EMPLOYER_SETTLEMENT_AT_APPLICATION', '报名时尚未落实工作单位');
    assert.equal(deferred.body.result, 'RECORDED_RECOMPUTE_DEFERRED');
    confirmDb('03-deferred', deferred, 'EMPLOYER_SETTLEMENT_AT_APPLICATION', 'DECLARED_MET', 3, 'WRITTEN');
    await factRow('EMPLOYER_SETTLEMENT_AT_APPLICATION').getByRole('button', { name: '恢复重算', exact: true }).waitFor();
    pass('真实重算故障：回答已独立提交，WRITTEN台账与可点击恢复入口保留');
  } finally { fixture.setRecomputeFailure(false); }
  const recovered = await answer('EMPLOYER_SETTLEMENT_AT_APPLICATION', '恢复重算');
  assert.deepEqual(recovered.request, deferred.request, 'Recovery must replay the exact original action');
  assert.equal(recovered.url, deferred.url, 'Recovery retains the action date');
  confirmDb('04-recovered', recovered, 'EMPLOYER_SETTLEMENT_AT_APPLICATION', 'DECLARED_MET', 3);
  assert.equal(recovered.body.profileVersionAfter, deferred.body.profileVersionAfter, 'No second profile write');
  await session(['SOCIAL_INSURANCE_AT_APPLICATION'], recovered.body.profileVersionAfter);
  pass('实际点击恢复：同钥匙补重算，无重复记录与资料版本写入');
  await exactRestoration(['SOCIAL_INSURANCE_AT_APPLICATION'], recovered.body.profileVersionAfter, '05-reloaded');
  const social = await answer('SOCIAL_INSURANCE_AT_APPLICATION', '报名时无社保缴纳记录');
  confirmDb('06-social', social, 'SOCIAL_INSURANCE_AT_APPLICATION', 'DECLARED_MET', 4);
  await session([], social.body.profileVersionAfter);
  const current = await question('第二个岗位怎么样');
  assert.deepEqual(current.decisions.map(d => d.jobId), [m.secondJobId]);
  assert.equal(current.decisions[0].eligibilityStatus, 'CONDITIONAL', 'Self-report cannot become officially verified eligible');
  assert.equal(current.profileVersion, social.body.profileVersionAfter);
  pass('刷新后继续最后一项：问题清空，本人声明仅到条件可报');

  assert.equal((await watchRead()).items.length, 0);
  const watch = await business(() => decisionRows().getByRole('button', { name: '关注', exact: true }).click(), `/watched-jobs/${m.secondJobId}`, 'PUT');
  assert.equal(watch.request.seenStatus, current.decisions[0].eligibilityStatus);
  assert.equal(watch.request.seenEvaluatorVersion, current.decisions[0].evaluatorVersion);
  const watchedRow = page.locator(`.watchlist-panel li[data-job-id="${m.secondJobId}"]`);
  await watchedRow.waitFor();
  let read = (await watchRead()).items[0];
  assert.equal(read.baselineMissing, false);
  assert.equal(read.changedSinceLastSeen, false);
  assert.equal(read.readState, 'UNCHANGED');
  let db = snapshot('07-watched');
  assert.equal(db.watches.length, 1);
  assert.equal(db.watches[0].last_seen_status, 'CONDITIONAL');
  assert.equal(db.watches[0].last_seen_evaluator_version, current.decisions[0].evaluatorVersion);
  pass('空清单经真实关注按钮立即出现，显示版本已建立数据库基线');

  fixture.setRecomputeFailure(true);
  try {
    const unavailable = await business(() => page.getByRole('button', { name: '刷新关注清单', exact: true }).click(), '/watched-jobs', 'GET');
    assert.equal(unavailable.body.items[0].readState, 'UNAVAILABLE');
    assert.equal(unavailable.body.items[0].errorCode, 'EVALUATION_FAILED');
    assert.equal(unavailable.body.items[0].evaluationCounted, true);
    assert.equal(unavailable.body.items[0].currentStatus, null);
    await page.getByRole('heading', { name: '1 个关注岗位这次无法判断变化', exact: true }).waitFor();
    await watchedRow.getByText('这个岗位这次读不到结论', { exact: true }).waitFor();
    assert.equal(snapshot('07a-watch-unavailable').watches[0].last_seen_status, 'CONDITIONAL');
    pass('真实关注评估失败显示无法判断而非无变化，保留原展示基线且失败计入预算');
  } finally { fixture.setRecomputeFailure(false); }
  const refreshed = await business(() => page.getByRole('button', { name: '刷新关注清单', exact: true }).click(), '/watched-jobs', 'GET');
  assert.equal(refreshed.body.items[0].readState, 'UNCHANGED');
  await page.getByRole('heading', { name: '关注岗位暂无结论变化', exact: true }).waitFor();

  await factRow('POLITICAL_AFFILIATION').getByRole('button', { name: '更改政治面貌回答', exact: true }).click();
  const changed = await answer('POLITICAL_AFFILIATION', '群众／其他');
  assert.equal(changed.body.result, 'CHANGE_REQUIRES_ACKNOWLEDGEMENT');
  assert.notEqual(changed.request.idempotencyKey, political.request.idempotencyKey, 'New intentional answer needs a new action key');
  assert.equal(snapshot('08-before-acknowledging-change').ledger.length, 4);
  const acknowledged = await answer('POLITICAL_AFFILIATION', '确认修改');
  assert.equal(acknowledged.request.idempotencyKey, changed.request.idempotencyKey);
  assert.equal(acknowledged.request.acknowledgedChange, true);
  confirmDb('09-verdict-changed', acknowledged, 'POLITICAL_AFFILIATION', 'NON_MEMBER', 5);
  await session([], acknowledged.body.profileVersionAfter);
  await page.locator(`.watchlist-panel li[data-job-id="${m.secondJobId}"][data-changed="true"]`).waitFor();
  read = (await watchRead()).items[0];
  assert.equal(read.lastSeenStatus, 'CONDITIONAL');
  assert.equal(read.currentStatus, 'INELIGIBLE');
  assert.equal(read.changedSinceLastSeen, true);
  pass('新动作使用新钥匙，明确确认修改后结论变为不可报且关注显示变化');
  await page.screenshot({ path: path.join(output, 'verdict-change-before-reload.png'), fullPage: true });
  await exactRestoration([], acknowledged.body.profileVersionAfter, '10-change-survives-reload');
  await page.locator(`.watchlist-panel li[data-job-id="${m.secondJobId}"][data-changed="true"]`).waitFor();
  db = snapshot('11-before-seen');
  assert.equal(db.watches[0].last_seen_status, 'CONDITIONAL', 'Reload must not consume the unseen transition');
  const shownVersion = await watchedRow.locator('.watch-version code').innerText();
  const seen = await business(() => watchedRow.getByRole('button', { name: '知道了', exact: true }).click(), `/watched-jobs/${m.secondJobId}/acknowledgements`);
  assert.deepEqual(seen.request, { seenStatus: 'INELIGIBLE', seenEvaluatorVersion: shownVersion });
  await page.locator(`.watchlist-panel li[data-job-id="${m.secondJobId}"][data-changed="false"]`).waitFor();
  db = snapshot('12-final');
  assert.equal(db.watches[0].last_seen_status, 'INELIGIBLE');
  assert.equal(db.watches[0].last_seen_evaluator_version, shownVersion);
  assert.equal((await watchRead()).items[0].readState, 'UNCHANGED');
  pass('点击知道了只确认屏幕展示版本；真实基线更新后才清除变化');
  const foreign = await context.request.get(`${m.baseUrl}/api/v1/candidates/01992f09-0000-7000-8000-000000000001/agent-queries/${sessionId}`);
  assert.equal(foreign.status(), 404);
  assert.equal((await foreign.json()).code, 'SESSION_NOT_FOUND');
  const gate = await context.request.post(`${m.baseUrl}/api/v1/candidates/${m.candidateId}/agent-runs`, { data: { question: '查岗位' } });
  assert.equal(gate.status(), 503);
  assert.equal((await gate.json()).code, 'PLANNER_UNAVAILABLE');
  const browserCandidateIds = report.apiTraffic.map(request => new URL(request.url).pathname.match(/\/candidates\/([^/]+)/)?.[1]).filter(Boolean);
  assert(browserCandidateIds.length > 0);
  assert(browserCandidateIds.every(id => id === m.candidateId), 'Browser must not read or write the migrated default candidate');
  snapshot('13-ownership-readonly');
  assert.equal(report.errors.length, 0, report.errors.join('\n'));
  pass('会话跨候选人读取被拒、动态工具503关闭、原候选人哈希未改、浏览器仅访问合成候选人且无运行时错误');
  await page.screenshot({ path: path.join(output, 'completed.png'), fullPage: true });
  report.outcome = 'PASS';
} catch (error) {
  report.outcome = 'FAIL';
  report.failure = String(error.stack ?? error);
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {});
  console.error(report.failure);
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  writeFileSync(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  await browser.close();
  console.log(`${report.outcome}: ${report.steps.length} checkpoints; ${path.join(output, 'report.json')}`);
}
