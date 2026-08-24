import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { CareerPlanPage } from './CareerPlanPage'

const plan = {
  candidateId: '01992f09-0000-7000-8000-000000000001', targetYear: 2027, asOf: '2026-08-22',
  candidateSnapshot: { displayName: '测试候选人', birthDate: '1992-12-31', gender: 'FEMALE', profileVersion: 'v1', educationSummary: '本科已完成，硕士预计 2027 毕业', expectedMasterGraduationYear: 2027 },
  currentScenario: { code: 'MASTER_IN_PROGRESS', label: '境外硕士在读', description: '本科已完成，境外硕士预计毕业；学位与留服认证须按公告截止时点判断。', effectiveFrom: '2026-08-22', current: true },
  graduateTrack: { code: 'TARGET_YEAR_GRADUATE', label: '2027 届境外硕士应届生候选', detail: '届别上进入目标年度当届通道；最终可报性取决于学位、留服认证和公告截止时点。', outcome: 'CONDITIONALLY_ELIGIBLE' },
  futureScenarios: [
    { code: 'DEGREE_PENDING_VERIFICATION', label: '硕士待认证', description: '学位已取得，留服待完成。', effectiveFrom: '2027-06-01', current: false },
    { code: 'MASTER_VERIFIED', label: '硕士已认证', description: '可判断硕士门槛岗位。', effectiveFrom: '2027-07-01', current: false },
  ],
  recommendedRoutes: [
    { code: 'PUBLIC_TECH', label: '事业单位信息技术岗', priorityScore: 68, priorityLabel: '五项证据加权决策指数，不是录取概率', historicalJobCount: 56, eventCount: 18, formalJobCount: 43, organizations: ['杭州市河湖管理中心'], jobFamilies: ['INFORMATION_SYSTEMS'], applicableScenarios: ['MASTER_IN_PROGRESS', 'MASTER_VERIFIED'], advantages: ['计算机本科与中级职称可复用'], risks: ['逐条核验经历'], preparationFocus: ['职测与综应'], representativeJobs: [{ jobId: 'job-1', organizationName: '杭州市河湖管理中心', title: '信息技术', year: 2026, sourceUrl: 'https://example.gov.cn/job-1', evidenceComplete: true, scenarioOutcomes: [{ scenarioCode: 'MASTER_IN_PROGRESS', outcome: 'CONDITIONALLY_ELIGIBLE', reasons: ['境外硕士在读'] }], historicalActual: { scenarioCode: 'HISTORICAL_ACTUAL', outcome: 'INELIGIBLE', reasons: ['2026 公告限 2026 届'] }, targetYearAnalog: { scenarioCode: 'TARGET_YEAR_ANALOG', outcome: 'CONDITIONALLY_ELIGIBLE', reasons: ['投影到 2027 届后进入当届范围'] } }], scenarioBreakdowns: [{ scenarioCode: 'MASTER_IN_PROGRESS', eligible: 12, conditionallyEligible: 8, uncertain: 30, ineligible: 6, notes: [] }, { scenarioCode: 'DEGREE_PENDING_VERIFICATION', eligible: 18, conditionallyEligible: 10, uncertain: 24, ineligible: 4, notes: [] }, { scenarioCode: 'MASTER_VERIFIED', eligible: 28, conditionallyEligible: 2, uncertain: 22, ineligible: 4, notes: [] }], scoreComponents: [{ code: 'ELIGIBILITY_READINESS', label: '目标年份资格准备度', score: 54, weight: 30, basis: '目标年度类比：可报 12、条件可报 8、待确认 30、不可报 6', evidenceBacked: true }, { code: 'EXPERIENCE_ADVANTAGE', label: '已核验经历优势', score: 0, weight: 25, basis: '工作经历尚未核验，不计优势分', evidenceBacked: false }, { code: 'EMPLOYMENT_STABILITY', label: '正式用工占比', score: 76, weight: 20, basis: '43/56 个历史岗位为正式用工', evidenceBacked: true }, { code: 'HISTORICAL_SUPPLY', label: '历史供给强度', score: 100, weight: 15, basis: '56 个岗位', evidenceBacked: true }, { code: 'PREPARATION_REUSE', label: '准备内容复用', score: 100, weight: 10, basis: '1/1 个岗位族匹配', evidenceBacked: true }], evidenceStrength: 'MODERATE', rankingState: 'RANKED', rankingReason: '目前已连接来源覆盖完成，参与排名' },
    { code: 'GOVERNMENT_SOE_DIGITAL', label: '政府国企数字化岗', priorityScore: null, priorityLabel: '暂不排名', historicalJobCount: 0, eventCount: 0, formalJobCount: 0, organizations: [], jobFamilies: [], applicableScenarios: [], advantages: [], risks: ['来源待接入'], preparationFocus: ['持续建立官方来源'], representativeJobs: [], scenarioBreakdowns: [], scoreComponents: [], evidenceStrength: 'INSUFFICIENT', rankingState: 'NOT_COVERED', rankingReason: '尚未接入目标来源，暂不排名' },
  ],
  ageWindows: [{ year: 2028, referenceDate: '2028-03-31', maximumAge: 35, candidateAge: 35, eligible: true, label: '2028 春季仍在 35 周岁窗口内', basis: '按完整生日计算', conditional: true }],
  recruitmentWindows: [{ month: 3, eventCount: 9, label: '3 月预计关注窗口', basis: '历史事件月份' }],
  examPatterns: [{ subject: '职业能力倾向测验', eventCount: 8 }],
  examSummary: { totalEvents: 18, writtenExamConfirmed: 8, writtenExamNotRequired: 1, writtenExamNotPublished: 3, writtenExamNotCollected: 4, writtenExamParseFailed: 1, writtenExamReviewRequired: 0, writtenExamUnknown: 1, professionalTestConfirmed: 2, professionalTestNotRequired: 5, professionalTestNotPublished: 3, professionalTestNotCollected: 7, professionalTestParseFailed: 0, professionalTestReviewRequired: 0, professionalTestUnknown: 1, interviewConfirmed: 7, interviewNotRequired: 0, interviewNotPublished: 4, interviewNotCollected: 5, interviewParseFailed: 0, interviewReviewRequired: 1, interviewUnknown: 1, subjects: [{ subject: '职业能力倾向测验', eventCount: 8 }], interviewMethods: [{ subject: '结构化面试', eventCount: 7 }], applicationToWrittenExamSamples: 6, averageApplicationToWrittenExamDays: 29 },
  processWindows: [{ stage: 'APPLICATION_START', month: 3, eventCount: 9 }, { stage: 'WRITTEN_EXAM', month: 4, eventCount: 8 }, { stage: 'INTERVIEW', month: 5, eventCount: 7 }],
  historicalSummary: [{ year: 2024, jobCount: 39, eventCount: 12, formalJobCount: 30, coverageComplete: true }, { year: 2025, jobCount: 43, eventCount: 14, formalJobCount: 34, coverageComplete: false }],
  qualificationRisks: [{ code: 'CREDENTIAL_VERIFICATION', severity: 'HIGH', title: '境外硕士与留服认证', detail: '认证完成前不视为满足硕士门槛。' }],
  actionTimeline: [{ startsOn: '2026-08-22', endsOn: '2026-12-31', title: '核实画像与材料证据', detail: '补工作证明。', status: 'NOW' }],
  dataCoverage: { complete: false, sourceYearCount: 6, completeSourceYearCount: 4, incompleteSourceYears: ['HZ:2025（PARTIAL）'], warnings: ['数据尚未补齐；缺失来源不得解释为零招聘。'], failedSections: [], loadedAt: '2026-08-22T12:00:00Z' },
  configuredCoverage: { complete: false, sourceYearCount: 6, completeSourceYearCount: 4, gaps: ['HZ:2025（PARTIAL）'] },
  targetMarketCoverage: { targetCount: 20, connected: 2, partial: 1, failed: 0, notConnected: 17, routes: [{ routeCode: 'PUBLIC_TECH', targetCount: 7, connected: 2, partial: 0, failed: 0, notConnected: 5, marketComplete: false }, { routeCode: 'GOVERNMENT_SOE_DIGITAL', targetCount: 3, connected: 0, partial: 0, failed: 0, notConnected: 3, marketComplete: false }] },
  analysisCoverage: { sourceCount: 2, eventCount: 18, jobCount: 56, evidenceCompleteJobs: 43, loadedAt: '2026-08-22T12:00:00Z' },
  generatedAt: '2026-08-22T12:00:00Z', algorithmVersion: 'career-plan-v3',
}

describe('CareerPlanPage', () => {
  beforeEach(() => localStorage.setItem('career-os.selected-candidate', plan.candidateId))
  afterEach(() => { localStorage.clear(); vi.unstubAllGlobals() })

  it('turns evidence into a readable semi-public career plan with job drill-down', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(plan), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<AppProviders><CareerPlanPage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '我的半体制规划' })).toBeInTheDocument()
    expect(screen.getByText('当前：本科已完成，硕士预计 2027 毕业')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '2027 届境外硕士应届生候选' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '应届通道' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '社会人员通道' })).toBeVisible()
    expect(screen.getAllByText('境外硕士在读').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByRole('heading', { name: '事业单位信息技术岗', level: 2 })).toBeInTheDocument()
    expect(screen.getByText('2028 春季仍在 35 周岁窗口内')).toBeInTheDocument()
    expect(screen.getByText('五项证据加权决策指数，不是录取概率')).toBeInTheDocument()
    expect(screen.getAllByText('12').length).toBeGreaterThanOrEqual(2)
    expect(screen.getAllByText('条件可报')).toHaveLength(3)
    expect(screen.getAllByText('硕士待认证').length).toBeGreaterThanOrEqual(2)
    expect(screen.getAllByText('硕士已认证').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('尚未接入目标来源，暂不排名')).toBeVisible()
    expect(screen.queryByText('路线 4')).not.toBeInTheDocument()
    expect(screen.getByText('官网说明另行通知/尚未发布')).toBeVisible()
    expect(screen.getByText('系统尚未采集该通知')).toBeVisible()
    expect(screen.getByText(/已接入 2 \/ 20/)).toBeVisible()
    expect(screen.getByText(/工作经历尚未核验，不计优势分/)).toBeInTheDocument()
    expect(screen.getByText(/数据尚未补齐/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /信息技术/ })).toHaveAttribute('href', '/jobs/job-1')
    expect(screen.queryByText(/录取概率.*%/)).not.toBeInTheDocument()
  })

  it('pauses route recommendations when historical evidence cannot be read', async () => {
    const failedPlan = {
      ...plan,
      dataCoverage: { ...plan.dataCoverage, failedSections: ['HISTORY'], warnings: ['历史岗位统计本次读取失败；页面中的空值不得解释为零招聘。'] },
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(failedPlan), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<AppProviders><CareerPlanPage /></AppProviders>)

    expect(await screen.findByText('历史数据本次未读到，暂不生成主攻路线。')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '历史数据读取失败，路线排名已暂停' })).toBeInTheDocument()
    expect(screen.getByText('排名暂停')).toBeInTheDocument()
    expect(screen.queryByText('主攻 事业单位信息技术岗')).not.toBeInTheDocument()
  })
})
