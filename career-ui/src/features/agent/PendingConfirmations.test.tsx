import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { PendingConfirmations } from './PendingConfirmations'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const sessionId = '01992f09-0000-7000-8000-0000000000aa'
const jobId = '01992f09-0000-7000-8000-0000000000bb'

const items = [{
  factKey: 'POLITICAL_AFFILIATION',
  question: '候选人政治面貌尚未确认',
  jobPostingId: jobId,
}]

const deferred = {
  result: 'RECORDED_RECOMPUTE_DEFERRED', evidenceStrength: 'SELF_REPORTED',
  profileVersionBefore: 'p1', profileVersionAfter: 'p2',
  message: '已按你本人的声明记录。岗位结论尚未重算完成。', pendingChange: null, changes: null,
}

function json(value: unknown) {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function renderPanel() {
  render(<AppProviders>
    <PendingConfirmations candidateId={candidateId} sessionId={sessionId} items={items} />
  </AppProviders>)
}

describe('PendingConfirmations', () => {
  afterEach(() => vi.unstubAllGlobals())

  /** 为什么问要写出来。只给一个选择题而不说理由，用户无从判断该不该答。 */
  it('explains why the question is being asked', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderPanel()

    expect(screen.getByText('候选人政治面貌尚未确认')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '中共党员' })).toBeInTheDocument()
  })

  /**
   * 回答之前就要说清这是本人声明。系统没有见过任何材料，
   * 让用户以为答一句就等于查证过，比不问更糟。
   */
  it('says the answer is self-reported before anything is answered', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderPanel()

    expect(screen.getByText(/本人声明/)).toBeInTheDocument()
    expect(screen.getByText(/不会把它当作官方核实/)).toBeInTheDocument()
  })

  /** 幂等钥匙按"这轮会话的这个字段"固定，重试不会写第二次。 */
  it('sends a stable idempotency key with the session', async () => {
    const fetch = vi.fn().mockResolvedValue(json({
      result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2',
      message: '已按你本人的声明记录。', pendingChange: null, changes: null,
    }))
    vi.stubGlobal('fetch', fetch)
    renderPanel()

    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    await vi.waitFor(() => expect(fetch).toHaveBeenCalled())

    const body = JSON.parse(fetch.mock.calls[0][1].body)
    expect(body.idempotencyKey).toBe(`${sessionId}:POLITICAL_AFFILIATION`)
    expect(body.sessionId).toBe(sessionId)
    expect(body.value).toBe('CPC_MEMBER')
    expect(body.acknowledgedChange).toBe(false)
  })

  /**
   * 改写既有答案要先看清改的是什么，再点一次。第一次请求不带确认标记，
   * 后端因此只回"要改什么"，不写；确认之后才带标记重发。
   */
  it('requires a second click to overwrite a different existing answer', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({
        result: 'CHANGE_REQUIRES_ACKNOWLEDGEMENT', evidenceStrength: 'SELF_REPORTED',
        profileVersionBefore: 'p1', profileVersionAfter: 'p1',
        message: '这会把「政治面貌」从「NON_MEMBER」改成「CPC_MEMBER」。改完要重算才知道各岗位结论有没有变。',
        pendingChange: { factKey: 'POLITICAL_AFFILIATION', from: 'NON_MEMBER', to: 'CPC_MEMBER' },
        changes: null,
      }))
      .mockResolvedValueOnce(json({
        result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
        profileVersionBefore: 'p1', profileVersionAfter: 'p2',
        message: '已按你本人的声明记录。', pendingChange: null, changes: null,
      }))
    vi.stubGlobal('fetch', fetch)
    renderPanel()

    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    expect(await screen.findByText('NON_MEMBER → CPC_MEMBER')).toBeInTheDocument()
    // 还没写入，也没有承诺会解锁多少岗位。
    expect(screen.queryByText(/解锁/)).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '确认修改' }))
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))

    expect(JSON.parse(fetch.mock.calls[0][1].body).acknowledgedChange).toBe(false)
    expect(JSON.parse(fetch.mock.calls[1][1].body).acknowledgedChange).toBe(true)
  })

  /** 展示的是真的重算出来的前后状态，不是承诺。 */
  it('shows the recomputed before and after status per job', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2',
      message: '已按你本人的声明记录。', pendingChange: null,
      changes: {
        previousProfileVersion: 'p1', currentProfileVersion: 'p2', available: true, message: null,
        newlyEligibleCount: 1, resolvedUncertaintyCount: 1, newlyIneligibleCount: 0,
        affectedJobs: [{
          jobId, title: '信息中心技术岗', organizationName: '杭州市信息中心',
          previousStatus: 'NEEDS_CONFIRMATION', currentStatus: 'ELIGIBLE',
          reasons: ['政治面貌：待确认 → 可报'], deepLink: `/opportunities/${jobId}`,
        }],
      },
    })))
    renderPanel()

    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))

    expect(await screen.findByText('信息中心技术岗')).toBeInTheDocument()
    expect(screen.getByText('待确认 → 可报')).toBeInTheDocument()
    expect(screen.getByText('政治面貌：待确认 → 可报')).toBeInTheDocument()
  })

  /**
   * 算不出变化时要说算不出来，不能显示三个 0——那会读成"确认了也没用"，
   * 而实际是"没有可靠的对照基线"。
   */
  it('says the difference is unavailable instead of showing zeroes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2',
      message: '已按你本人的声明记录。', pendingChange: null,
      changes: {
        previousProfileVersion: 'p1', currentProfileVersion: 'p2', available: false,
        message: '没有找到该资料版本的历史岗位结论，无法可靠计算变化。',
        newlyEligibleCount: null, resolvedUncertaintyCount: null, newlyIneligibleCount: null,
        affectedJobs: [],
      },
    })))
    renderPanel()

    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))

    expect(await screen.findByText('没有找到该资料版本的历史岗位结论，无法可靠计算变化。')).toBeInTheDocument()
    expect(screen.queryByText(/新增可报/)).not.toBeInTheDocument()
  })

  /** 重算没做完要说出来，不能装作结论已经刷新。 */
  it('says the verdicts are not refreshed when the recompute was deferred', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(deferred)))
    renderPanel()

    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))

    expect(await screen.findByText(/岗位结论还没重算完/)).toBeInTheDocument()
  })

  /**
   * 重算没做完时要有一个按钮，不是一句"稍后重试"。
   *
   * <p>没有按钮的话，这条恢复路径在页面上根本不存在——接口能单独调用，不等于用户走得通。
   */
  it('offers a button to finish the recompute instead of telling the user to retry later', async () => {
    const fetch = vi.fn().mockResolvedValue(json(deferred))
    vi.stubGlobal('fetch', fetch)
    renderPanel()

    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    await userEvent.click(await screen.findByRole('button', { name: '继续重算' }))

    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    // 用原来那把幂等钥匙重放：只接着做重算，不会再写一次，也不会再推高一次资料版本。
    const first = JSON.parse(fetch.mock.calls[0][1].body)
    const retry = JSON.parse(fetch.mock.calls[1][1].body)
    expect(retry.idempotencyKey).toBe(first.idempotencyKey)
    expect(retry.value).toBe(first.value)
  })

  /**
   * 每条问题都要标出是哪个岗位提出的。
   *
   * <p>不标的话，用户看到的是一句悬空的"你的政治面貌是？"——他既不知道为什么现在问，
   * 也不知道答了对哪个岗位有影响。
   */
  it('shows which job asked for the confirmation', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderPanel()

    expect(screen.getByRole('link', { name: '这个岗位' }))
      .toHaveAttribute('href', `/opportunities/${jobId}`)
  })
})
