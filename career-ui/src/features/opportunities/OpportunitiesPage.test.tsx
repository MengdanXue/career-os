import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { AppProviders } from '../../app/AppProviders'
import { OpportunitiesPage } from './OpportunitiesPage'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const admissionSummary = {
  total: 2291, raw: 2291, parsed: 0, normalized: 0,
  reviewRequired: 0, verified: 0, rejected: 0, failed: 0,
  included: 0, excluded: 0, needsReview: 2291, opportunityReady: 0,
}

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

function summary(value = admissionSummary) {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function matches(items: unknown[] = []) {
  return new Response(JSON.stringify({ items, page: 0, size: 20, total: items.length }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function route(input: RequestInfo | URL, decisions: Response, candidateMatches: Response = matches()) {
  const url = String(input)
  if (url.includes('/api/v1/job-library/summary')) return summary()
  if (url.includes('/job-matches')) return candidateMatches
  return decisions
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
      return route(input, url.includes('tier=T2') ? page([decision({ tier: 'T2', jobTitle: '高校数据平台岗' })]) : page([decision()]))
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
    vi.stubGlobal('fetch', vi.fn(async input => route(input, page([decision({ eligibilityStatus: 'INELIGIBLE', tier: 'EXCLUDED', warnings: ['学历不符合公告硬性条件'] })]))))
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
    vi.stubGlobal('fetch', vi.fn(async input => route(input, page([unknown]))))
    render(<AppProviders><OpportunitiesPage /></AppProviders>)

    await userEvent.click(await screen.findByRole('button', { name: /查看 信息中心 Java 岗/ }))
    await waitFor(() => expect(screen.getAllByText('待核实').length).toBeGreaterThan(0))
    expect(screen.queryByText('0/20')).not.toBeInTheDocument()
  })

  it('opens a dossier from an agent deep link', async () => {
    const linked = decision()
    window.history.replaceState({}, '', `/opportunities/${linked.jobId}?tier=T1`)
    vi.stubGlobal('fetch', vi.fn(async input => route(input, page([linked]))))

    render(<AppProviders><Routes><Route path="/opportunities/:jobId" element={<OpportunitiesPage />} /></Routes></AppProviders>)

    expect(await screen.findByText('岗位适配')).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: '信息中心 Java 岗 岗位档案' })).toBeInTheDocument()
  })

  it('explains an empty trusted pool instead of implying collection found nothing', async () => {
    vi.stubGlobal('fetch', vi.fn(async input => route(input, page([]))))

    render(<AppProviders><OpportunitiesPage /></AppProviders>)

    expect(await screen.findByText('目前没有通过证据准入的可信岗位')).toBeInTheDocument()
    expect(screen.getByText('原始岗位不会自动进入 T1/T2/T3')).toBeInTheDocument()
    expect(screen.queryByText('这一层暂时没有岗位')).not.toBeInTheDocument()
  })

  it('shows official workbook matches while employment identity awaits evidence', async () => {
    const candidateMatches = matches([{
      jobId: crypto.randomUUID(), jobTitle: '信息中心工作人员', organizationName: '杭州市西溪医院',
      location: '杭州', eligibilityStatus: 'ELIGIBLE', fitScore: 65, coveragePercent: 60,
      employmentType: 'UNKNOWN', employmentIdentityConfirmed: false,
      admissionReasons: ['TARGET_TECHNICAL_ROLE', 'EMPLOYMENT_IDENTITY_UNKNOWN'],
      warnings: ['用工身份待官方证据确认'], sourceUrl: 'https://hrss.hangzhou.gov.cn/notice',
      jobContentFingerprint: 'a'.repeat(64), externalJobCode: 'HZ-101',
      headcount: 1, jobFamily: 'INFORMATION_SYSTEMS', minimumEducation: 'MASTER',
      exactMajors: ['计算机科学与技术'], acceptedGraduationYears: [], maximumAge: 38,
      ageReferenceDate: '2026-08-01', minimumExperienceYears: null,
      requiredProfessionalTitles: ['中级'], duties: '医院信息系统建设和数据库管理',
      eventTitle: '2026年杭州市西溪医院公开招聘', publishedOn: '2026-07-01',
      applicationStartsOn: '2026-07-10', applicationEndsOn: '2026-07-20',
    }])
    vi.stubGlobal('fetch', vi.fn(async input => route(input, page([]), candidateMatches)))

    render(<AppProviders><OpportunitiesPage /></AppProviders>)

    expect(await screen.findByText('杭州市西溪医院')).toBeInTheDocument()
    expect(screen.getByText(/共 1 个符合画像/)).toBeInTheDocument()
    expect(screen.getByText('信息中心工作人员')).toBeInTheDocument()
    expect(screen.getByText('用工身份待确认')).toBeInTheDocument()
    const trigger = screen.getByRole('button', { name: '查看 信息中心工作人员 完整详情' })
    await userEvent.click(trigger)
    const detail = screen.getByRole('complementary', { name: '信息中心工作人员 官网岗位详情' })
    expect(detail).toBeInTheDocument()
    expect(detail).toHaveFocus()
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('HZ-101')).toBeInTheDocument()
    expect(screen.getByText('医院信息系统建设和数据库管理')).toBeInTheDocument()
    expect(screen.getByText('计算机科学与技术')).toBeInTheDocument()
    expect(screen.getByText('2026-07-10 至 2026-07-20')).toBeInTheDocument()
    expect(screen.getByText('官网未明确用工性质')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看官方公告' })).toHaveAttribute('href', 'https://hrss.hangzhou.gov.cn/notice')
    await userEvent.click(screen.getByRole('button', { name: '关闭官网岗位详情' }))
    expect(trigger).toHaveFocus()
  })
})
