import { render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { TodayPage } from './TodayPage'
import type { PersonalActions, WorkbenchSummary } from './todayApi'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const workbench: WorkbenchSummary = {
  candidateId,
  generatedAt: '2026-08-23T10:00:00Z',
  tierCounts: { t1: 3, t2: 5, t3: 8, excluded: 2 },
  deadlines: [],
  changes: { available: true, message: null, items: [] },
  sources: { available: true, message: null, healthy: 2, enabled: 2, issues: [] },
  reviews: { available: true, message: null, pending: 535 },
}
const actions: PersonalActions = {
  candidateId,
  asOf: '2026-08-23',
  available: true,
  message: null,
  items: [
    {
      id: 'deadline:11111111-1111-1111-1111-111111111111', kind: 'CURRENT_JOB_DEADLINE', priority: 1,
      title: '信息中心 Java 岗即将截止', reason: '杭州市数字事业中心的官方报名截止日期已进入 14 天提醒窗口。',
      affectedObjectCount: 1, dueOn: '2026-08-28', evidenceStrength: 'DOCUMENTED',
      deepLink: '/opportunities/11111111-1111-1111-1111-111111111111',
    },
    {
      id: 'evidence:VERIFY_EMPLOYMENT_HISTORY', kind: 'CANDIDATE_EVIDENCE', priority: 2,
      title: '补齐可核验工作经历', reason: '旧资料记录 7 年，但硬资格只能使用逐段核验的工作经历。',
      affectedObjectCount: 12, dueOn: null, evidenceStrength: 'NONE', deepLink: '/profile#employment-history',
    },
    {
      id: 'change:33333333-3333-3333-3333-333333333333', kind: 'TARGET_JOB_CHANGE', priority: 3,
      title: '2 个目标岗位条件有变化', reason: '目标岗位发生关键字段变化，需要重新查看条件。',
      affectedObjectCount: 2, dueOn: null, evidenceStrength: 'DOCUMENTED', deepLink: '/opportunities?changed=true',
    },
  ],
}

function json(value: unknown) {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function stubApi(actionResponse: PersonalActions = actions, workbenchResponse: WorkbenchSummary = workbench) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/personal-actions')) return json(actionResponse)
    if (url.includes('/workbench-summary')) return json(workbenchResponse)
    throw new Error(`Unexpected request: ${url}`)
  }))
}

describe('TodayPage', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('career-os.selected-candidate', candidateId)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('renders at most three backend-ordered personal actions before secondary data', async () => {
    stubApi()
    render(<AppProviders><TodayPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '今天最重要的 3 件事' })).toBeInTheDocument()
    const list = screen.getByLabelText('今日个人行动')
    const cards = within(list).getAllByRole('article')
    expect(cards).toHaveLength(3)
    expect(within(cards[0]).getByRole('heading', { name: '信息中心 Java 岗即将截止' })).toBeInTheDocument()
    expect(within(cards[0]).getByText(/2026年8月28日/)).toBeInTheDocument()
    expect(within(cards[1]).getByRole('heading', { name: '补齐可核验工作经历' })).toBeInTheDocument()
    expect(within(cards[1]).getByRole('link', { name: '现在处理' })).toHaveAttribute('href', '/profile#employment-history')
    expect(screen.getByRole('heading', { name: '你的机会概览' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看 T1' })).toHaveAttribute('href', '/opportunities?tier=T1')
    expect(screen.queryByText(/535 条数据等待你复核/)).not.toBeInTheDocument()
    expect(screen.queryByText('535')).not.toBeInTheDocument()
  })

  it('shows a directional empty state when there is no personal action', async () => {
    stubApi({ ...actions, items: [] })
    render(<AppProviders><TodayPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '今天没有新的资格、截止或准备事项' })).toBeInTheDocument()
    expect(screen.getByText('你可以继续完善资料，或查看当前 T1 机会。')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '完善我的资料' })).toHaveAttribute('href', '/profile')
  })

  it('keeps useful actions visible while explaining partial calculation failure once', async () => {
    stubApi({ ...actions, available: false, message: '目标岗位影响暂时无法计算', items: [actions.items[1]] })
    render(<AppProviders><TodayPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '今天最重要的 1 件事' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '补齐可核验工作经历' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('目标岗位影响暂时无法计算')
    expect(screen.getAllByText('目标岗位影响暂时无法计算')).toHaveLength(1)
  })
})
