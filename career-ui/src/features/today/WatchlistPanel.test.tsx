import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { WatchlistPanel } from './WatchlistPanel'
import type { WatchedJob } from './watchlistApi'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const jobId = '01992f09-0000-7000-8000-0000000000cc'
const asOf = '2026-08-24'

function job(overrides: Partial<WatchedJob> = {}): WatchedJob {
  return {
    jobPostingId: jobId,
    jobTitle: '信息中心技术岗',
    organizationName: '杭州市信息中心',
    lastSeenStatus: 'NEEDS_CONFIRMATION',
    currentStatus: 'ELIGIBLE',
    changedSinceLastSeen: true,
    baselineMissing: false,
    applicationEndsOn: '2026-09-01',
    applicationClosed: false,
    evaluatorVersion: 'v7',
    deepLink: `/opportunities/${jobId}`,
    ...overrides,
  }
}

function json(value: unknown) {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function renderPanel(items: WatchedJob[], fetch = vi.fn().mockResolvedValue(json({ candidateId, asOf, items }))) {
  vi.stubGlobal('fetch', fetch)
  render(<AppProviders><WatchlistPanel candidateId={candidateId} asOf={asOf} /></AppProviders>)
  return fetch
}

describe('WatchlistPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  /** 变化是用户回来要看的东西，所以要写出前后，不是只显示当前值。 */
  it('shows what the verdict changed from and to', async () => {
    renderPanel([job()])

    expect(await screen.findByText('待确认 → 可报')).toBeInTheDocument()
    expect(screen.getByText('1 个关注岗位的结论有变化')).toBeInTheDocument()
  })

  /**
   * 确认已看过时，带的是屏幕上那个结论，不是让服务端重新算。
   * 中间若又变了一次，用当前值推进会把那次变化一起吞掉。
   */
  it('acknowledges with the status shown on screen', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ candidateId, asOf, items: [job()] }))
      .mockResolvedValueOnce(json({ jobId, at: '2026-08-24T15:00:00Z' }))
      .mockResolvedValue(json({ candidateId, asOf, items: [] }))
    renderPanel([job()], fetch)

    await userEvent.click(await screen.findByRole('button', { name: '知道了' }))
    await vi.waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(1))

    const call = fetch.mock.calls.find(entry => String(entry[0]).includes('acknowledgements'))!
    expect(JSON.parse(call[1].body)).toEqual({ seenStatus: 'ELIGIBLE', seenEvaluatorVersion: 'v7' })
  })

  /** 没有变化的岗位不给"知道了"——没有要清掉的提示。 */
  it('offers no acknowledgement when nothing changed', async () => {
    renderPanel([job({ changedSinceLastSeen: false, lastSeenStatus: 'ELIGIBLE' })])

    expect(await screen.findByText('关注岗位暂无结论变化')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '知道了' })).not.toBeInTheDocument()
  })

  /** 结论仍是"可报"但窗口已经关了，只显示"可报"会让人以为还来得及。 */
  it('marks a closed application window even when the verdict is still eligible', async () => {
    renderPanel([job({ changedSinceLastSeen: false, currentStatus: 'ELIGIBLE', applicationClosed: true })])

    expect(await screen.findByText('报名已截止')).toBeInTheDocument()
    expect(screen.getByText('可报')).toBeInTheDocument()
  })

  /**
   * 读不到就说读不到。显示成上次的结论会让人以为那还是当前结论，
   * 显示成"没变化"则是替它下了一个没有依据的结论。
   */
  it('says a job could not be read rather than showing a stale verdict', async () => {
    renderPanel([job({ currentStatus: null, jobTitle: null, organizationName: null, changedSinceLastSeen: false })])

    expect(await screen.findByText('这个岗位这次读不到结论')).toBeInTheDocument()
    expect(screen.getByText(/不是没有变化/)).toBeInTheDocument()
    expect(screen.queryByText('待确认')).not.toBeInTheDocument()
  })

  /** 关注不是报名：这个面板里不会出现任何提交报名的入口。 */
  it('offers no way to apply', async () => {
    renderPanel([job()])
    await screen.findByText('待确认 → 可报')

    for (const label of [/报名$/, /立即报名/, /一键报名/, /提交报名/]) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument()
    }
  })

  /**
   * 没有基准就发现不了变化，而界面此前只在"变了"时才给按钮——基准永远补不上。
   * 所以缺基准必须自己带一个入口，否则这个洞自己不会愈合。
   */
  it('offers a way to start tracking when no baseline exists', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ candidateId, asOf, items: [
        job({ lastSeenStatus: null, changedSinceLastSeen: false, baselineMissing: true }),
      ] }))
      .mockResolvedValueOnce(json({ jobId, at: '2026-08-24T15:00:00Z' }))
      .mockResolvedValue(json({ candidateId, asOf, items: [] }))
    renderPanel([], fetch)

    expect(await screen.findByText('尚未开始跟踪变化')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '以当前结论为基准，开始跟踪变化' }))
    await vi.waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(1))

    const call = fetch.mock.calls.find(entry => String(entry[0]).includes('acknowledgements'))!
    expect(JSON.parse(call[1].body).seenStatus).toBe('ELIGIBLE')
  })

  /** 有基准的正常条目不该出现补基准的入口。 */
  it('does not offer the baseline action once a baseline exists', async () => {
    renderPanel([job({ changedSinceLastSeen: false, lastSeenStatus: 'ELIGIBLE' })])

    await screen.findByText('可报')
    expect(screen.queryByText('尚未开始跟踪变化')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /开始跟踪变化/ })).not.toBeInTheDocument()
  })

  it('renders nothing when no job is watched', async () => {
    renderPanel([])

    await vi.waitFor(() => expect(screen.queryByText(/关注清单/)).not.toBeInTheDocument())
  })
})
