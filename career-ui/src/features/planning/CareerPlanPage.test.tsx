import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { CareerPlanPage } from './CareerPlanPage'

const plan = {
  candidateId: '01992f09-0000-7000-8000-000000000001', targetYear: 2027, asOf: '2026-08-22',
  candidateSnapshot: { displayName: '测试候选人', birthDate: '1992-12-31', gender: 'FEMALE', profileVersion: 'v1', educationSummary: '本科已完成，硕士预计 2027 毕业', expectedMasterGraduationYear: 2027 },
  currentScenario: { code: 'PRE_GRADUATION', label: '本科阶段', description: '本科已完成；境外硕士尚未取得。', effectiveFrom: '2026-08-22', current: true },
  futureScenarios: [
    { code: 'DEGREE_PENDING_VERIFICATION', label: '硕士待认证', description: '学位已取得，留服待完成。', effectiveFrom: '2027-06-01', current: false },
    { code: 'MASTER_VERIFIED', label: '硕士已认证', description: '可判断硕士门槛岗位。', effectiveFrom: '2027-07-01', current: false },
  ],
  recommendedRoutes: [{ code: 'PUBLIC_TECH', label: '事业单位信息技术岗', priorityScore: 91, priorityLabel: '决策指数，不是录取概率', historicalJobCount: 56, eventCount: 18, formalJobCount: 43, organizations: ['杭州市河湖管理中心'], jobFamilies: ['INFORMATION_SYSTEMS'], applicableScenarios: ['PRE_GRADUATION', 'MASTER_VERIFIED'], advantages: ['计算机本科与中级职称可复用'], risks: ['逐条核验经历'], preparationFocus: ['职测与综应'], representativeJobs: [{ jobId: 'job-1', organizationName: '杭州市河湖管理中心', title: '信息技术', year: 2026, sourceUrl: 'https://example.gov.cn/job-1', evidenceComplete: true }], evidenceStrength: 'MODERATE' }],
  ageWindows: [{ year: 2028, referenceDate: '2028-03-31', maximumAge: 35, candidateAge: 35, eligible: true, label: '2028 春季仍在 35 周岁窗口内', basis: '按完整生日计算', conditional: true }],
  recruitmentWindows: [{ month: 3, eventCount: 9, label: '3 月预计关注窗口', basis: '历史事件月份' }],
  examPatterns: [{ subject: '职业能力倾向测验', eventCount: 8 }],
  historicalSummary: [{ year: 2024, jobCount: 39, eventCount: 12, formalJobCount: 30, coverageComplete: true }, { year: 2025, jobCount: 43, eventCount: 14, formalJobCount: 34, coverageComplete: false }],
  qualificationRisks: [{ code: 'CREDENTIAL_VERIFICATION', severity: 'HIGH', title: '境外硕士与留服认证', detail: '认证完成前不视为满足硕士门槛。' }],
  actionTimeline: [{ startsOn: '2026-08-22', endsOn: '2026-12-31', title: '核实画像与材料证据', detail: '补工作证明。', status: 'NOW' }],
  dataCoverage: { complete: false, sourceYearCount: 6, completeSourceYearCount: 4, incompleteSourceYears: ['HZ:2025（PARTIAL）'], warnings: ['数据尚未补齐；缺失来源不得解释为零招聘。'], loadedAt: '2026-08-22T12:00:00Z' },
  generatedAt: '2026-08-22T12:00:00Z', algorithmVersion: 'career-plan-v1',
}

describe('CareerPlanPage', () => {
  beforeEach(() => localStorage.setItem('career-os.selected-candidate', plan.candidateId))
  afterEach(() => { localStorage.clear(); vi.unstubAllGlobals() })

  it('turns evidence into a readable semi-public career plan with job drill-down', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(plan), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<AppProviders><CareerPlanPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '我的半体制规划' })).toBeInTheDocument()
    expect(screen.getByText('当前：本科已完成，硕士预计 2027 毕业')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '事业单位信息技术岗' })).toBeInTheDocument()
    expect(screen.getByText('2028 春季仍在 35 周岁窗口内')).toBeInTheDocument()
    expect(screen.getByText('决策指数，不是录取概率')).toBeInTheDocument()
    expect(screen.getByText(/数据尚未补齐/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /信息技术/ })).toHaveAttribute('href', '/opportunities/job-1')
    expect(screen.queryByText(/录取概率.*%/)).not.toBeInTheDocument()
  })
})
