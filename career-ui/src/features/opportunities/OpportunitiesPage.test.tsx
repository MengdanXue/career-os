import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { AppProviders } from '../../app/AppProviders'
import { OpportunitiesPage } from './OpportunitiesPage'

const candidateId = '01992f09-0000-7000-8000-000000000001'

function decision(overrides: Record<string, unknown> = {}) {
  return {
    decisionId: crypto.randomUUID(), candidateId, jobId: crypto.randomUUID(),
    jobTitle: '信息中心 Java 岗', organizationName: '杭州市数字事业中心', location: '杭州',
    eligibilityStatus: 'ELIGIBLE', tier: 'T1', recommendationStatus: 'RECOMMENDED',
    fit: { score: 82, coveragePercent: 75, dimensions: [{ type: 'SKILL_FIT', achievedPoints: 32, maximumPoints: 40, factStatus: 'EXPLICIT', reasonCode: 'SKILLS_MATCH', explanation: 'Java 与数据治理相符', evidenceIds: ['11111111-1111-1111-1111-111111111111'] }] },
    stability: { score: 88, coveragePercent: 60, dimensions: [{ type: 'EMPLOYMENT_SECURITY', achievedPoints: 40, maximumPoints: 40, factStatus: 'EXPLICIT', reasonCode: 'ESTABLISHMENT', explanation: '公告明确为事业编制', evidenceIds: ['22222222-2222-2222-2222-222222222222'] }] },
    explanation: '资格通过，稳定性较高。', warnings: [], evidenceIds: ['11111111-1111-1111-1111-111111111111'],
    evaluatorVersion: 'decision-v1', profileVersion: 'profile-v1', jobContentFingerprint: 'a'.repeat(64), assessedAt: '2026-08-20T10:00:00Z', disclaimer: '机会决策指数，不是录取概率',
    ...overrides,
  }
}

function page(items: unknown[]) {
  return new Response(JSON.stringify({ items, page: 0, size: 20, total: items.length, disclaimer: '机会决策指数，不是录取概率' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('OpportunitiesPage', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/opportunities')
    localStorage.clear()
    localStorage.setItem('career-os.selected-candidate', candidateId)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('keeps T1, T2, and T3 in separate queues', async () => {
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      return url.includes('tier=T2') ? page([decision({ tier: 'T2', jobTitle: '高校数据平台岗' })]) : page([decision()])
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><OpportunitiesPage /></AppProviders>)
    expect(await screen.findByText('信息中心 Java 岗')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: /T2/ }))
    expect(await screen.findByText('高校数据平台岗')).toBeInTheDocument()
    expect(screen.queryByText('信息中心 Java 岗')).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([url]) => String(url).includes('tier=T2'))).toBe(true)
  })

  it('shows hard blockers before softer fit scores', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(page([decision({ eligibilityStatus: 'INELIGIBLE', tier: 'EXCLUDED', warnings: ['学历不符合公告硬性条件'] })])))
    render(<AppProviders><OpportunitiesPage initialTier="EXCLUDED" /></AppProviders>)

    await userEvent.click(await screen.findByRole('button', { name: /查看 信息中心 Java 岗/ }))
    const blocker = await screen.findByText('学历不符合公告硬性条件')
    const fit = screen.getByText('岗位适配')
    expect(blocker.compareDocumentPosition(fit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('机会决策指数，不是录取概率')).toBeInTheDocument()
  })

  it('calls unknown evidence pending instead of scoring it as zero', async () => {
    const unknown = decision({
      fit: { score: 62, coveragePercent: 30, dimensions: [{ type: 'RESEARCH_FIT', achievedPoints: 0, maximumPoints: 20, factStatus: 'UNKNOWN', reasonCode: 'MISSING_EVIDENCE', explanation: '公告没有提供研究方向要求', evidenceIds: [] }] },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(page([unknown])))
    render(<AppProviders><OpportunitiesPage /></AppProviders>)

    await userEvent.click(await screen.findByRole('button', { name: /查看 信息中心 Java 岗/ }))
    await waitFor(() => expect(screen.getAllByText('待核实').length).toBeGreaterThan(0))
    expect(screen.queryByText('0/20')).not.toBeInTheDocument()
  })

  it('opens a dossier from an agent deep link', async () => {
    const linked = decision()
    window.history.replaceState({}, '', `/opportunities/${linked.jobId}?tier=T1`)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(page([linked])))

    render(<AppProviders><Routes><Route path="/opportunities/:jobId" element={<OpportunitiesPage />} /></Routes></AppProviders>)

    expect(await screen.findByText('岗位适配')).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: '信息中心 Java 岗 岗位档案' })).toBeInTheDocument()
  })
})
