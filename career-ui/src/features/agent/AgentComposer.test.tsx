import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { AgentComposer } from './AgentComposer'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const answer = {
  question: '本周最值得准备什么？',
  answer: '优先核对杭州市数字事业中心岗位的专业目录，并在截止日前准备证明材料。',
  decisions: [{
    decisionId: crypto.randomUUID(), candidateId, jobId: crypto.randomUUID(), jobTitle: '信息中心 Java 岗', organizationName: '杭州市数字事业中心', location: '杭州', eligibilityStatus: 'ELIGIBLE', tier: 'T1', recommendationStatus: 'RECOMMENDED',
    fit: { score: 82, coveragePercent: 75, dimensions: [] }, stability: { score: 88, coveragePercent: 60, dimensions: [] }, explanation: '资格通过', warnings: [], evidenceIds: [], evaluatorVersion: 'decision-v1', profileVersion: 'profile-v1', jobContentFingerprint: 'a'.repeat(64), assessedAt: '2026-08-20T10:00:00Z', disclaimer: '机会决策指数，不是录取概率',
  }],
  modelPhrased: false,
  fallbackUsed: false,
  disclaimer: '机会决策指数，不是录取概率',
  sessionId: '01992f09-0000-7000-8000-0000000000aa',
}

function json(value: unknown) { return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } }) }

describe('AgentComposer', () => {
  beforeEach(() => { localStorage.clear(); localStorage.setItem('career-os.selected-candidate', candidateId) })
  afterEach(() => vi.unstubAllGlobals())

  it('answers with structured local decisions when no model is configured', async () => {
    const fetch = vi.fn().mockResolvedValue(json(answer))
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '本周最值得准备什么？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))

    expect(await screen.findByText(/优先核对杭州市数字事业中心/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看 信息中心 Java 岗' })).toHaveAttribute('href', expect.stringContaining('/opportunities/'))
    expect(screen.getByText('T1')).toBeInTheDocument()
  })

  it('keeps a useful local answer when optional wording enhancement is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...answer, fallbackUsed: true })))
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '为什么推荐这个岗位？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))

    expect(await screen.findByText('模型叙述未通过校验，以下为程序生成的确定性结论')).toBeInTheDocument()
    expect(screen.queryByText(/LLM|OpenAI|模型失败/i)).not.toBeInTheDocument()
  })

  /**
   * 关注清单此前只有接口没有入口，任何岗位都进不去，整个功能不可达。
   * 结果行上的「关注」就是那个入口，并且要把屏幕上的结论作为基准一起送上去——
   * 没有基准的话，第一次变化发现不了。
   */
  it('can add a job to the watchlist with the verdict shown on screen', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json(answer))
      .mockResolvedValue(json({ jobId: answer.decisions[0].jobId, at: '2026-08-24T15:00:00Z' }))
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '杭州有哪些稳定岗位？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))
    await screen.findByText(/优先核对杭州市数字事业中心/)

    await userEvent.click(screen.getByRole('button', { name: '关注' }))
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))

    const [url, init] = fetch.mock.calls[1]
    expect(String(url)).toContain('/watched-jobs/')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body)).toEqual({ seenStatus: 'ELIGIBLE', seenEvaluatorVersion: 'decision-v1' })
    expect(await screen.findByText('已加入关注')).toBeInTheDocument()
  })

  // 第二句话必须带上第一句返回的会话 ID。不带的话每句都是新的一轮，
  // "第二个怎么样"就没有那份列表可指，而系统会拿重新排出来的第二名认真作答。
  it('carries the session id into the next question', async () => {
    const fetch = vi.fn().mockResolvedValue(json(answer))
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '杭州有哪些稳定岗位？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))
    await screen.findByText(/优先核对杭州市数字事业中心/)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '第二个怎么样？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))

    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    expect(JSON.parse(fetch.mock.calls[0][1].body).sessionId).toBeNull()
    expect(JSON.parse(fetch.mock.calls[1][1].body).sessionId).toBe('01992f09-0000-7000-8000-0000000000aa')
  })

  // 拦截只写进服务端日志等于没拦截——用户必须看得见为什么这段叙述没展示。
  it('shows why a model narrative was rejected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...answer,
      fallbackUsed: true,
      violations: ['叙述包含数值；所有数值必须来自确定性事实块'],
    })))
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '为什么推荐这个岗位？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))

    expect(await screen.findByText('叙述包含数值；所有数值必须来自确定性事实块')).toBeInTheDocument()
  })

  /**
   * 刷新之后要接着走，不是从头再问一遍。
   *
   * <p>会话只活在页面内存里的话，按一次 F5，待确认事项和它们依据的资料版本一起消失。
   * 回答到一半刷新最糟：已经写进去的那条没了着落，用户不知道自己答过没有。
   */
  it('recovers the previous round after a refresh', async () => {
    localStorage.setItem('career-os.agent-session', '01992f09-0000-7000-8000-0000000000aa')
    const fetch = vi.fn().mockResolvedValue(json({
      sessionId: '01992f09-0000-7000-8000-0000000000aa',
      profileVersion: 'profile-7',
      stale: false,
      openTask: null,
      pendingQuestion: null,
      jobIdsInOrder: ['01992f09-0000-7000-8000-0000000000bb'],
      pendingConfirmations: [{
        factKey: 'POLITICAL_AFFILIATION',
        question: '候选人政治面貌尚未确认',
        jobPostingId: '01992f09-0000-7000-8000-0000000000bb',
        answered: false,
      }],
    }))
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    expect(await screen.findByText('候选人政治面貌尚未确认')).toBeInTheDocument()
    expect(String(fetch.mock.calls[0][0]))
      .toContain('/agent-queries/01992f09-0000-7000-8000-0000000000aa')
  })

  /**
   * 资料在这期间变了就要说出来。
   *
   * <p>不说的话，页面会拿着旧序号继续问"第二个怎么样"——重新排出来的第二个可能是另一个岗位，
   * 而用户看不出它换了对象。
   */
  it('warns that a recovered listing no longer matches the current profile', async () => {
    localStorage.setItem('career-os.agent-session', '01992f09-0000-7000-8000-0000000000aa')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      sessionId: '01992f09-0000-7000-8000-0000000000aa',
      profileVersion: 'profile-7', stale: true, openTask: null, pendingQuestion: null,
      jobIdsInOrder: [], pendingConfirmations: [],
    })))
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    expect(await screen.findByText(/上一份列表的排序不再对应当前结论/)).toBeInTheDocument()
  })

  /** 上一轮已经过期或不是本人的，就安静地当新开一轮，不用一条错误挡住输入框。 */
  it('starts a fresh round when the stored session cannot be recovered', async () => {
    localStorage.setItem('career-os.agent-session', '01992f09-0000-7000-8000-0000000000aa')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 'SESSION_NOT_FOUND', detail: '没有这轮会话' }),
        { status: 404, headers: { 'Content-Type': 'application/json' } })))
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    expect(await screen.findByLabelText('问题示例')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  /**
   * 刷新之后，已经答过的问题不能被再问一遍。
   *
   * <p>原样回放会让用户分不出"还没答"和"答过了"——他会以为系统没收到他的回答，
   * 再答一次；也不能把它删掉，那样他会以为这一条凭空消失了。
   */
  it('marks already-answered questions instead of asking them again', async () => {
    localStorage.setItem('career-os.agent-session', '01992f09-0000-7000-8000-0000000000aa')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      sessionId: '01992f09-0000-7000-8000-0000000000aa',
      profileVersion: 'profile-7', stale: false, openTask: null, pendingQuestion: null,
      jobIdsInOrder: [],
      pendingConfirmations: [
        { factKey: 'POLITICAL_AFFILIATION', question: '候选人政治面貌尚未确认',
          jobPostingId: '01992f09-0000-7000-8000-0000000000bb', answered: true },
        { factKey: 'GENDER', question: '候选人性别尚未确认',
          jobPostingId: '01992f09-0000-7000-8000-0000000000cc', answered: false },
      ],
    })))
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    expect(await screen.findByText(/已答过：政治面貌/)).toBeInTheDocument()
    expect(screen.getByText('候选人性别尚未确认')).toBeInTheDocument()
    // 答过的那一条不再给出可点的选项。
    expect(screen.queryByRole('button', { name: '中共党员' })).not.toBeInTheDocument()
  })

  /**
   * 刷新之后要说明用户在回答什么。
   *
   * <p>只把问题原样摆回去还不够：他记得的是自己原本要办的那件事，不是系统问过的那句话。
   * 两个都不摆，他答完一句"余杭"会不知道这句话去了哪里。
   */
  it('shows the outstanding question and the task it was asked for', async () => {
    localStorage.setItem('career-os.agent-session', '01992f09-0000-7000-8000-0000000000aa')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      sessionId: '01992f09-0000-7000-8000-0000000000aa',
      profileVersion: 'profile-7', stale: false,
      openTask: '有什么合适的',
      pendingQuestion: '你想看哪个城市或区县的岗位？',
      jobIdsInOrder: [], pendingConfirmations: [],
    })))
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    expect(await screen.findByText(/上次问你：你想看哪个城市或区县的岗位？/)).toBeInTheDocument()
    expect(screen.getByText(/为了：有什么合适的/)).toBeInTheDocument()
  })
})
