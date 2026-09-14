import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { confirmationAttemptsKey, PendingConfirmations } from './PendingConfirmations'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const sessionId = '01992f09-0000-7000-8000-0000000000aa'
const jobId = '01992f09-0000-7000-8000-0000000000bb'

const items = [{
  factKey: 'POLITICAL_AFFILIATION',
  question: '候选人政治面貌尚未确认',
  jobPostingId: jobId,
}]

function json(value: unknown) {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function renderPanel() {
  return render(<AppProviders>
    <PendingConfirmations candidateId={candidateId} sessionId={sessionId} items={items} />
  </AppProviders>)
}

describe('PendingConfirmations', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

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

  /** 钥匙标识一次明确动作，而不是永久绑定会话中的字段。 */
  it('sends a new action idempotency key with the session', async () => {
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
    expect(body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(body.idempotencyKey).not.toBe(`${sessionId}:POLITICAL_AFFILIATION`)
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
    expect(JSON.parse(fetch.mock.calls[1][1].body).idempotencyKey).toBe(JSON.parse(fetch.mock.calls[0][1].body).idempotencyKey)
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      result: 'RECORDED_RECOMPUTE_DEFERRED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2',
      message: '已按你本人的声明记录。岗位结论尚未重算完成。', pendingChange: null, changes: null,
    })))
    renderPanel()

    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))

    expect(await screen.findByText(/岗位结论还没刷新/)).toBeInTheDocument()
  })

  it('restores a deferred attempt after remount and clicks recovery with the exact original request', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({
      result: 'RECORDED_RECOMPUTE_DEFERRED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '已记录但重算失败', pendingChange: null, changes: null,
    })).mockResolvedValueOnce(json({
      result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '已恢复且没有第二次写入', pendingChange: null, changes: null,
    }))
    vi.stubGlobal('fetch', fetch)
    const first = render(<AppProviders><PendingConfirmations candidateId={candidateId} sessionId={sessionId} items={items} /></AppProviders>)
    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    await screen.findByRole('button', { name: '恢复重算' })
    first.unmount()
    // 服务端已移除回答完的问题，恢复按钮仍必须从原操作记录恢复，且不能自动发送。
    render(<AppProviders><PendingConfirmations candidateId={candidateId} sessionId={sessionId} items={[]} /></AppProviders>)
    expect(fetch).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: '恢复重算' }))
    expect(await screen.findByText('已恢复且没有第二次写入')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[1][0]).toBe(fetch.mock.calls[0][0])
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(JSON.parse(fetch.mock.calls[0][1].body))
  })

  it('retries a network-uncertain action with the same key and value, not a new write', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new Error('lost response')).mockResolvedValueOnce(json({
      result: 'ALREADY_RECORDED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '此前已记录', pendingChange: null, changes: null,
    }))
    vi.stubGlobal('fetch', fetch); renderPanel()
    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    await userEvent.click(await screen.findByRole('button', { name: '重试原请求' }))
    expect(await screen.findByText('此前已记录')).toBeInTheDocument()
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(JSON.parse(fetch.mock.calls[0][1].body))
  })

  it('creates a new key for a changed answer in the same session while keeping its acknowledgement key', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ result: 'RECORDED', evidenceStrength: 'SELF_REPORTED', profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '首次成功', pendingChange: null, changes: null }))
      .mockResolvedValueOnce(json({ result: 'CHANGE_REQUIRES_ACKNOWLEDGEMENT', evidenceStrength: 'SELF_REPORTED', profileVersionBefore: 'p2', profileVersionAfter: 'p2', message: '等待明确覆盖授权', pendingChange: { factKey: 'POLITICAL_AFFILIATION', from: 'CPC_MEMBER', to: 'NON_MEMBER' }, changes: null }))
      .mockResolvedValueOnce(json({ result: 'RECORDED', evidenceStrength: 'SELF_REPORTED', profileVersionBefore: 'p2', profileVersionAfter: 'p3', message: '新动作成功', pendingChange: null, changes: null }))
    vi.stubGlobal('fetch', fetch); renderPanel()
    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    await userEvent.click(await screen.findByRole('button', { name: '更改政治面貌回答' }))
    await userEvent.click(screen.getByRole('button', { name: '群众／其他' }))
    await userEvent.click(await screen.findByRole('button', { name: '确认修改' }))
    expect(await screen.findByText('新动作成功')).toBeInTheDocument()
    const bodies = fetch.mock.calls.map(call => JSON.parse(call[1].body))
    expect(bodies[0].idempotencyKey).not.toBe(bodies[1].idempotencyKey)
    expect(bodies[2].idempotencyKey).toBe(bodies[1].idempotencyKey)
    expect(bodies[1].acknowledgedChange).toBe(false)
    expect(bodies[2].acknowledgedChange).toBe(true)
  })

  it('does not hide answer choices after a business rejection or call it recorded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      result: 'PROFILE_VERSION_CHANGED', evidenceStrength: 'SELF_REPORTED', profileVersionBefore: 'p2', profileVersionAfter: 'p2',
      message: '资料已变更，没有写入', pendingChange: null, changes: null,
    })))
    renderPanel(); await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    expect(await screen.findByText('资料已变更，没有写入')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '群众／其他' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更改政治面貌回答' })).not.toBeInTheDocument()
  })

  it('leaves an unknown answer pending without any business request', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); renderPanel()
    await userEvent.click(screen.getByRole('button', { name: '仍不确定，暂不回答' }))
    expect(screen.getByText(/保持待确认，没有保存为已满足/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '中共党员' })).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('blocks the next answer when question refresh fails and offers a read-only refresh retry', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
      profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '写入成功', pendingChange: null, changes: null }))
    const refresh = vi.fn().mockRejectedValueOnce(new Error('GET failed')).mockResolvedValueOnce(undefined)
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><PendingConfirmations candidateId={candidateId} sessionId={sessionId}
      items={[...items, { factKey: 'GENDER', question: '性别待确认', jobPostingId: jobId }]} onConfirmed={refresh} /></AppProviders>)
    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    expect(await screen.findByText(/剩余问题尚未刷新/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '女' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '重试刷新剩余问题' }))
    await vi.waitFor(() => expect(screen.getByRole('button', { name: '女' })).toBeEnabled())
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('clears an acknowledgement preview before sending and blocks new answers after a lost acknowledged receipt', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ result: 'CHANGE_REQUIRES_ACKNOWLEDGEMENT', evidenceStrength: 'SELF_REPORTED',
        profileVersionBefore: 'p1', profileVersionAfter: 'p1', message: '修改需要确认',
        pendingChange: { factKey: 'POLITICAL_AFFILIATION', from: 'NON_MEMBER', to: 'CPC_MEMBER' }, changes: null }))
      .mockRejectedValueOnce(new Error('committed but response lost'))
      .mockResolvedValueOnce(json({ result: 'ALREADY_RECORDED', evidenceStrength: 'SELF_REPORTED',
        profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '原修改此前已记录', pendingChange: null, changes: null }))
    vi.stubGlobal('fetch', fetch)
    const first = render(<AppProviders><PendingConfirmations candidateId={candidateId} sessionId={sessionId}
      items={[...items, { factKey: 'GENDER', question: '性别待确认', jobPostingId: jobId }]} /></AppProviders>)
    await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    await userEvent.click(await screen.findByRole('button', { name: '确认修改' }))
    await screen.findByRole('button', { name: '重试原请求' })
    expect(screen.queryByRole('button', { name: '重新选择答案' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认修改' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '女' })).toBeDisabled()
    const saved = JSON.parse(sessionStorage.getItem(confirmationAttemptsKey(candidateId, sessionId))!)
    expect(saved.POLITICAL_AFFILIATION.response).toBeUndefined()
    expect(saved.POLITICAL_AFFILIATION.request.acknowledgedChange).toBe(true)
    first.unmount(); renderPanel()
    expect(fetch).toHaveBeenCalledTimes(2)
    await userEvent.click(screen.getByRole('button', { name: '重试原请求' }))
    expect(await screen.findByText('原修改此前已记录')).toBeInTheDocument()
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual(JSON.parse(fetch.mock.calls[1][1].body))
    expect(JSON.parse(fetch.mock.calls[2][1].body).idempotencyKey).toBe(JSON.parse(fetch.mock.calls[0][1].body).idempotencyKey)
  })

  it('fails closed before sending when the original request cannot be persisted', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'QuotaExceededError') })
    renderPanel(); await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    expect(await screen.findByText(/尚未发送新的回答/)).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '中共党员' })).toBeEnabled()
  })

  it('retains a received success in memory when receipt storage fails and restores the persisted original request after remount', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
        profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '已成功记录这次回答', pendingChange: null, changes: null }))
      .mockResolvedValueOnce(json({ result: 'ALREADY_RECORDED', evidenceStrength: 'SELF_REPORTED',
        profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: '原请求已有成功回执', pendingChange: null, changes: null }))
    vi.stubGlobal('fetch', fetch)
    const original = Storage.prototype.setItem
    let writes = 0
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key, value) {
      writes += 1
      if (writes === 2) throw new DOMException('receipt too large', 'QuotaExceededError')
      original.call(this, key, value)
    })
    const first = renderPanel(); await userEvent.click(screen.getByRole('button', { name: '中共党员' }))
    expect(await screen.findByText('已成功记录这次回答')).toBeInTheDocument()
    expect(screen.getByText(/已收到业务回执，但浏览器未能保存恢复信息/)).toBeInTheDocument()
    expect(screen.queryByText(/尚未发送新的回答/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试原请求' })).not.toBeInTheDocument()
    expect(JSON.parse(sessionStorage.getItem(confirmationAttemptsKey(candidateId, sessionId))!).POLITICAL_AFFILIATION.response).toBeUndefined()
    first.unmount(); storage.mockRestore(); renderPanel()
    expect(fetch).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: '重试原请求' }))
    expect(await screen.findByText('原请求已有成功回执')).toBeInTheDocument()
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(JSON.parse(fetch.mock.calls[0][1].body))
  })

  it.each([null, [], { POLITICAL_AFFILIATION: null }, { POLITICAL_AFFILIATION: { request: [] } }])(
    'ignores malformed local attempt containers without crashing: %j', stored => {
      sessionStorage.setItem(confirmationAttemptsKey(candidateId, sessionId), JSON.stringify(stored))
      const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); renderPanel()
      expect(screen.getByRole('button', { name: '中共党员' })).toBeEnabled()
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it.each([null, { result: 'RECORDED', changes: [] }, { result: 'RECORDED', evidenceStrength: 'SELF_REPORTED',
    profileVersionBefore: 'p1', profileVersionAfter: 'p2', message: 'damaged receipt', changes: { available: true, affectedJobs: [null] } }])(
    'preserves a valid original request when its local display receipt is malformed: %j', response => {
      const request = { factKey: 'POLITICAL_AFFILIATION', value: 'CPC_MEMBER', sessionId,
        idempotencyKey: 'original-surviving-key', acknowledgedChange: true }
      sessionStorage.setItem(confirmationAttemptsKey(candidateId, sessionId), JSON.stringify({
        POLITICAL_AFFILIATION: { request, asOf: '2026-09-14', item: null, response },
      }))
      const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); renderPanel()
      expect(screen.getByRole('button', { name: '重试原请求' })).toBeEnabled()
      expect(screen.queryByRole('button', { name: '中共党员' })).not.toBeInTheDocument()
      expect(fetch).not.toHaveBeenCalled()
      expect(JSON.parse(sessionStorage.getItem(confirmationAttemptsKey(candidateId, sessionId))!).POLITICAL_AFFILIATION.request).toEqual(request)
    },
  )
})
