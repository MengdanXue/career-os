import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { AgentComposer } from './AgentComposer'
import { readAgentSnapshot, saveAgentSnapshot } from './agentSessionStorage'
import type { AgentResponse, AgentSessionResponse } from './agentApi'
import { WatchlistPanel } from '../today/WatchlistPanel'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const sessionId = '01992f09-0000-7000-8000-0000000000aa'
const jobId = '01992f09-0000-7000-8000-0000000000bb'
const firstJobId = '01992f09-0000-7000-8000-0000000000cc'
const pending = [
  { factKey: 'POLITICAL_AFFILIATION', question: '请确认政治面貌', jobPostingId: jobId },
  { factKey: 'GENDER', question: '请确认性别', jobPostingId: jobId },
]
const answer: AgentResponse = {
  question: '第二个怎么样？', answer: '程序生成的确定性事实块，机会决策指数不是录取概率。',
  decisions: [{
    decisionId: crypto.randomUUID(), candidateId, jobId, jobTitle: '信息中心 Java 岗', organizationName: '杭州市数字事业中心', location: '杭州', eligibilityStatus: 'NEEDS_CONFIRMATION', tier: 'T1', recommendationStatus: 'REVIEW',
    fit: { score: 82, coveragePercent: 75, dimensions: [] }, stability: { score: 88, coveragePercent: 60, dimensions: [] }, explanation: '待确认', warnings: [], evidenceIds: [], evaluatorVersion: 'decision-v1', profileVersion: 'profile-v1', jobContentFingerprint: 'a'.repeat(64), assessedAt: '2026-08-20T10:00:00Z', disclaimer: '机会决策指数，不是录取概率',
  }],
  modelPhrased: false, fallbackUsed: false, disclaimer: '机会决策指数，不是录取概率',
  sessionId, profileVersion: 'profile-v1', pendingConfirmations: pending,
}
function session(overrides: Partial<AgentSessionResponse> = {}): AgentSessionResponse {
  return { sessionId, profileVersion: 'profile-v1', currentProfileVersion: 'profile-v1', stale: false,
    jobIdsInOrder: [firstJobId, jobId], pendingConfirmations: pending, ...overrides }
}
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }) }
type Handler = (url: string, init: RequestInit) => Response | Promise<Response> | undefined
function server(handler?: Handler, response: AgentResponse = answer) {
  const fetch = vi.fn(async (raw: string, init: RequestInit = {}) => {
    const url = String(raw)
    const custom = await handler?.(url, init)
    if (custom) return custom
    if (url.endsWith(`/agent-queries/${sessionId}`)) return json(session())
    if (url.endsWith('/agent-queries') && init.method === 'POST') return json(response)
    if (url.includes('/watched-jobs/') && init.method === 'PUT') return json({ jobId, at: '2026-09-14T00:00:00Z' })
    throw new Error(`Unmocked request: ${init.method ?? 'GET'} ${url}`)
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function ask(value = '杭州有哪些稳定岗位？') {
  const box = screen.getByLabelText('向 Career OS 提问')
  await userEvent.clear(box)
  await userEvent.type(box, value)
  await userEvent.click(screen.getByRole('button', { name: '分析' }))
  await screen.findByRole('link', { name: '查看 信息中心 Java 岗' })
  await vi.waitFor(() => expect(screen.getByRole('button', { name: '分析' })).toBeEnabled())
}
function renderComposer() { return render(<AppProviders><AgentComposer expanded /></AppProviders>) }
function saveVerifiedAnswer() { saveAgentSnapshot(candidateId, { ...answer, canonicalJobIdsInOrder: [firstJobId, jobId] }) }

describe('AgentComposer', () => {
  beforeEach(() => { localStorage.clear(); sessionStorage.clear(); localStorage.setItem('career-os.selected-candidate', candidateId) })
  afterEach(() => vi.unstubAllGlobals())

  it('renders deterministic decisions and their explicit display version', async () => {
    server(); renderComposer(); await ask()
    expect(screen.getByText(answer.answer)).toBeInTheDocument()
    expect(screen.getByTestId('display-profile-version')).toHaveTextContent('profile-v1')
    expect(screen.getByTestId('remaining-confirmations')).toHaveTextContent('2')
    expect(screen.getByRole('link', { name: '查看 信息中心 Java 岗' }).closest('li')).toHaveAttribute('data-job-id', jobId)
  })

  it('retains deterministic legacy fallback and rejection reasons', async () => {
    server(undefined, { ...answer, fallbackUsed: true, violations: ['叙述包含未经核对的数值'] })
    renderComposer(); await ask()
    expect(screen.getByText('模型叙述未通过校验，以下为程序生成的确定性结论')).toBeInTheDocument()
    expect(screen.getByText('叙述包含未经核对的数值')).toBeInTheDocument()
  })

  it('carries the same session into ordinal follow-up without replacing its saved order', async () => {
    const fetch = server(); renderComposer(); await ask(); await ask('第二个怎么样？')
    const calls = fetch.mock.calls.filter(([url, init]) => url.endsWith('/agent-queries') && init?.method === 'POST')
    expect(calls).toHaveLength(2)
    expect(JSON.parse(calls[0][1]!.body as string).sessionId).toBeNull()
    expect(JSON.parse(calls[1][1]!.body as string).sessionId).toBe(sessionId)
    expect(screen.getByRole('region', { name: '会话恢复状态' })).toHaveAttribute('data-session-id', sessionId)
  })

  it('restores the exact session, remaining scoped question and selected job after remount, with no query POST', async () => {
    saveVerifiedAnswer()
    const fetch = server(url => url.endsWith(`/agent-queries/${sessionId}`) ? json(session({
      profileVersion: 'profile-v2', currentProfileVersion: 'profile-v2',
      pendingConfirmations: [
        { ...pending[0], answered: true }, pending[1],
        { factKey: 'SOCIAL_INSURANCE_AT_APPLICATION', question: '另一岗位的问题', jobPostingId: firstJobId },
      ],
    })) : undefined)
    renderComposer()
    await screen.findByRole('button', { name: '女' })
    expect(screen.queryByRole('button', { name: '中共党员' })).not.toBeInTheDocument()
    expect(screen.queryByText('另一岗位的问题')).not.toBeInTheDocument()
    expect(screen.getByTestId('remaining-confirmations')).toHaveTextContent('1')
    expect(screen.getByTestId('session-profile-version')).toHaveTextContent('profile-v2')
    expect(screen.getByTestId('display-profile-version')).toHaveTextContent('profile-v1')
    expect(screen.getByText(/上次展示的结论快照/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看 信息中心 Java 岗' }).closest('li')).toHaveAttribute('data-job-id', jobId)
    expect(fetch.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })

  it('refreshes remaining questions and version after first success before allowing the second confirmation', async () => {
    let version = 'profile-v1'; let remaining = pending
    const confirmations: { factKey: string; idempotencyKey: string }[] = []
    const fetch = server((url, init) => {
      if (url.endsWith(`/agent-queries/${sessionId}`)) return json(session({ profileVersion: version, currentProfileVersion: version, pendingConfirmations: remaining }))
      if (url.includes('/profile-confirmations?')) {
        const body = JSON.parse(init.body as string); confirmations.push(body)
        const before = version; version = `profile-v${confirmations.length + 1}`
        remaining = remaining.filter(item => item.factKey !== body.factKey)
        return json({ result: 'RECORDED', evidenceStrength: 'SELF_REPORTED', profileVersionBefore: before, profileVersionAfter: version,
          message: `${body.factKey} 成功业务回执`, pendingChange: null, changes: null })
      }
    })
    renderComposer(); await ask()
    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    await vi.waitFor(() => expect(screen.getByTestId('session-profile-version')).toHaveTextContent('profile-v2'))
    expect(screen.getByTestId('remaining-confirmations')).toHaveTextContent('1')
    await userEvent.click(screen.getByRole('button', { name: '女' }))
    expect(await screen.findByText('GENDER 成功业务回执')).toBeInTheDocument()
    await vi.waitFor(() => expect(screen.getByTestId('session-profile-version')).toHaveTextContent('profile-v3'))
    expect(screen.getByTestId('remaining-confirmations')).toHaveTextContent('0')
    expect(confirmations.map(item => item.factKey)).toEqual(['POLITICAL_AFFILIATION', 'GENDER'])
    expect(confirmations[0].idempotencyKey).not.toBe(confirmations[1].idempotencyKey)
    expect(fetch.mock.calls.filter(([url]) => url.endsWith(`/agent-queries/${sessionId}`))).toHaveLength(3)
  })

  it('invalidates an already loaded empty watchlist after adding the displayed job and exact version', async () => {
    let watched = false
    const fetch = server((url, init) => {
      if (url.includes('/watched-jobs?')) return json({ candidateId, asOf: '2026-09-14', items: watched ? [{
        jobPostingId: jobId, jobTitle: '关注中的第二岗', organizationName: '测试单位', lastSeenStatus: 'NEEDS_CONFIRMATION',
        currentStatus: 'NEEDS_CONFIRMATION', changedSinceLastSeen: false, baselineMissing: false, applicationClosed: false,
        applicationEndsOn: null, evaluatorVersion: 'decision-v1', deepLink: `/opportunities/${jobId}`,
      }] : [] })
      if (url.includes('/watched-jobs/') && init.method === 'PUT') { watched = true; return json({ jobId }) }
    })
    render(<AppProviders><WatchlistPanel candidateId={candidateId} asOf="2026-09-14" /><AgentComposer expanded /></AppProviders>)
    await screen.findByText('还没有关注任何岗位'); await ask()
    await userEvent.click(screen.getByRole('button', { name: '关注' }))
    expect(await screen.findByText('关注中的第二岗')).toBeInTheDocument()
    const call = fetch.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(call[1]!.body as string)).toEqual({ seenStatus: 'NEEDS_CONFIRMATION', seenEvaluatorVersion: 'decision-v1' })
  })

  it('keeps restoration failures visible and retries GET without reranking or business writes', async () => {
    saveVerifiedAnswer()
    let failed = true
    const fetch = server(url => url.endsWith(`/agent-queries/${sessionId}`) && failed ? json({ detail: '暂时不可读', code: 'TEMPORARY' }, 503) : undefined)
    renderComposer()
    expect(await screen.findByText(/原会话读取失败/)).toBeInTheDocument()
    expect(screen.queryByText(answer.answer)).not.toBeInTheDocument()
    failed = false
    await userEvent.click(screen.getByRole('button', { name: '刷新会话与问题' }))
    await screen.findByRole('button', { name: '女' })
    expect(fetch.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })

  it('does not promote a stale restored session version or allow answering stale questions', async () => {
    saveVerifiedAnswer()
    server(url => url.endsWith(`/agent-queries/${sessionId}`) ? json(session({ currentProfileVersion: 'external-v2', stale: true })) : undefined)
    renderComposer()
    expect(await screen.findByText(/旧问题与序号已过期/)).toBeInTheDocument()
    expect(screen.getByTestId('session-profile-version')).toHaveTextContent('profile-v1')
    expect(screen.getByRole('button', { name: '女' })).toBeDisabled()
  })

  it('does not restore another candidate or a snapshot outside the canonical session job list', async () => {
    saveAgentSnapshot('other-candidate', answer)
    const fetch = server(); const rendered = renderComposer()
    expect(fetch).not.toHaveBeenCalled(); rendered.unmount()
    saveVerifiedAnswer()
    server(url => url.endsWith(`/agent-queries/${sessionId}`) ? json(session({ jobIdsInOrder: [firstJobId] })) : undefined)
    renderComposer()
    expect(await screen.findByText(/岗位记录不一致/)).toBeInTheDocument()
    expect(screen.queryByText(answer.answer)).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '会话恢复状态' })).queryByTestId('remaining-confirmations')).not.toBeInTheDocument()
  })

  it('rejects a restored list with the same jobs in a different canonical order', async () => {
    const listAnswer = { ...answer, decisions: [{ ...answer.decisions[0], jobId: firstJobId, decisionId: 'first-job-decision' }, answer.decisions[0]] }
    saveAgentSnapshot(candidateId, { ...listAnswer, canonicalJobIdsInOrder: [firstJobId, jobId] })
    const fetch = server(url => url.endsWith(`/agent-queries/${sessionId}`)
      ? json(session({ jobIdsInOrder: [jobId, firstJobId] })) : undefined)
    renderComposer()
    expect(await screen.findByText(/岗位记录不一致/)).toBeInTheDocument()
    expect(screen.queryByText(answer.answer)).not.toBeInTheDocument()
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toEqual([firstJobId, jobId])
    expect(fetch.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })

  it('persists the full canonical order for a focused single-job answer and preserves it after remount', async () => {
    const fetch = server(); const first = renderComposer(); await ask('第二个怎么样？')
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toEqual([firstJobId, jobId])
    expect(readAgentSnapshot(candidateId)?.decisions.map(decision => decision.jobId)).toEqual([jobId])
    first.unmount(); renderComposer()
    expect(await screen.findByRole('button', { name: '女' })).toBeEnabled()
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toEqual([firstJobId, jobId])
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('does not manufacture a verified order for a legacy snapshot during read-only restoration', async () => {
    saveAgentSnapshot(candidateId, answer)
    const fetch = server(); renderComposer()
    expect(await screen.findByText(/缺少已核对的岗位顺序/)).toBeInTheDocument()
    expect(screen.queryByText(answer.answer)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '刷新会话与问题' }))
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toBeUndefined()
    expect(fetch.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })

  it('binds a new answer only to its own post-answer GET rather than a prior cached session order', async () => {
    saveVerifiedAnswer()
    let gets = 0
    let release: (response: Response) => void = () => {}
    server(url => {
      if (!url.endsWith(`/agent-queries/${sessionId}`)) return undefined
      gets += 1
      if (gets === 1) return json(session())
      return new Promise<Response>(resolve => { release = resolve })
    }, { ...answer, answer: '新列表的回答', decisions: [answer.decisions[0], { ...answer.decisions[0], jobId: firstJobId, decisionId: 'first-job-decision' }] })
    renderComposer()
    await screen.findByRole('button', { name: '女' })
    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '重新查询岗位列表')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))
    await vi.waitFor(() => expect(gets).toBe(2))
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toBeUndefined()
    expect(screen.queryByText('新列表的回答')).not.toBeInTheDocument()
    release(json(session({ jobIdsInOrder: [jobId, firstJobId] })))
    expect(await screen.findByText('新列表的回答')).toBeInTheDocument()
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toEqual([jobId, firstJobId])
  })

  it('does not bind a newly returned multi-job list to a GET with reversed order', async () => {
    server(undefined, { ...answer, decisions: [answer.decisions[0], { ...answer.decisions[0], jobId: firstJobId, decisionId: 'first-job-decision' }] })
    renderComposer()
    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '重新查询岗位列表')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))
    expect(await screen.findByText(/岗位记录不一致/)).toBeInTheDocument()
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toBeUndefined()
    expect(screen.queryByText(answer.answer)).not.toBeInTheDocument()
  })

  it('does not let an old in-flight GET bind the order of a newer answer', async () => {
    saveVerifiedAnswer()
    let gets = 0
    let releasePost: (response: Response) => void = () => {}
    let releaseOldGet: (response: Response) => void = () => {}
    let releaseNewGet: (response: Response) => void = () => {}
    server((url, init) => {
      if (url.endsWith('/agent-queries') && init.method === 'POST') {
        return new Promise<Response>(resolve => { releasePost = resolve })
      }
      if (!url.endsWith(`/agent-queries/${sessionId}`)) return undefined
      gets += 1
      if (gets === 1) return json(session())
      return new Promise<Response>(resolve => {
        if (gets === 2) releaseOldGet = resolve
        else releaseNewGet = resolve
      })
    })
    renderComposer(); await screen.findByRole('button', { name: '女' })
    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '重新查询岗位列表')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))
    await userEvent.click(screen.getByRole('button', { name: '刷新会话与问题' }))
    await vi.waitFor(() => expect(gets).toBe(2))
    releasePost(json({ ...answer, answer: '新的单岗位回答' }))
    await vi.waitFor(() => expect(gets).toBe(3))
    releaseOldGet(json(session()))
    await vi.waitFor(() => expect(readAgentSnapshot(candidateId)?.answer).toBe('新的单岗位回答'))
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toBeUndefined()
    expect(screen.queryByText('新的单岗位回答')).not.toBeInTheDocument()
    releaseNewGet(json(session({ jobIdsInOrder: [jobId, firstJobId] })))
    expect(await screen.findByText('新的单岗位回答')).toBeInTheDocument()
    expect(readAgentSnapshot(candidateId)?.canonicalJobIdsInOrder).toEqual([jobId, firstJobId])
  })
})
