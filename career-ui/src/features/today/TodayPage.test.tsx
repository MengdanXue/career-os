import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { TodayPage } from './TodayPage'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const base = {
  candidateId,
  generatedAt: '2026-08-20T10:00:00Z',
  tierCounts: { t1: 3, t2: 5, t3: 8, excluded: 2 },
  deadlines: [],
  changes: { available: true, message: null, items: [] },
  sources: { available: true, message: null, healthy: 2, enabled: 2, issues: [] },
  reviews: { available: true, message: null, pending: 0 },
}

function json(value: unknown) { return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } }) }

describe('TodayPage', () => {
  beforeEach(() => { localStorage.clear(); localStorage.setItem('career-os.selected-candidate', candidateId) })
  afterEach(() => vi.unstubAllGlobals())

  it('turns daily changes, deadlines, source issues and reviews into an attention queue', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...base,
      deadlines: [{ jobId: '11111111-1111-1111-1111-111111111111', jobTitle: '信息中心 Java 岗', organizationName: '杭州市数字事业中心', location: '杭州', tier: 'T1', deadline: '2026-08-28', daysRemaining: 8 }],
      changes: { available: true, message: null, items: [{ id: crypto.randomUUID(), changeType: 'UPDATED', sourceUri: 'https://example.gov.cn/jobs', jobDeltaSummary: { updated: 2 }, occurredAt: '2026-08-20T09:00:00Z' }] },
      sources: { available: true, message: null, healthy: 1, enabled: 2, issues: [{ id: crypto.randomUUID(), name: '浙江省人社厅', consecutiveFailureCount: 2, lastFailureAt: '2026-08-20T08:00:00Z' }] },
      reviews: { available: true, message: null, pending: 3 },
    })))

    render(<AppProviders><TodayPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '今天要处理 4 类事项' })).toBeInTheDocument()
    expect(screen.getByText('信息中心 Java 岗')).toBeInTheDocument()
    expect(screen.getByText('2 个岗位发生变更')).toBeInTheDocument()
    expect(screen.getByText('浙江省人社厅连续失败 2 次')).toBeInTheDocument()
    expect(screen.getByText('3 条数据等待你复核')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看 T1' })).toHaveAttribute('href', '/opportunities?tier=T1')
  })

  it('shows a calm empty state while keeping the tier ledger visible', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(base)))
    render(<AppProviders><TodayPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '今天没有必须处理的变化' })).toBeInTheDocument()
    expect(screen.getByText('T1')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })
})
