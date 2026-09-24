import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import type { CandidateMatch } from './candidateMatchApi'
import { ScopeDecisionPanel } from './ScopeDecisionPanel'

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

function match(overrides: Partial<CandidateMatch> = {}): CandidateMatch {
  return {
    jobId: '01992f09-0000-7000-8000-0000000000aa', externalJobCode: 'A-1', jobTitle: '信息中心工作人员',
    organizationName: '杭州市数据资源中心', location: '杭州', eligibilityStatus: 'ELIGIBLE', fitScore: 70,
    coveragePercent: 80, employmentType: 'PUBLIC_INSTITUTION_FORMAL', employmentIdentityConfirmed: true,
    admissionReasons: ['TARGET_TECHNICAL_ROLE'], warnings: [], actualEmployer: null, worksite: null,
    employmentEvidence: null, sourceUrl: 'https://example.gov.cn/notice', jobContentFingerprint: 'f'.repeat(64),
    headcount: 1, jobFamily: 'INFORMATION_SYSTEMS', minimumEducation: 'MASTER', exactMajors: [],
    acceptedGraduationYears: [], maximumAge: null, ageReferenceDate: null, minimumExperienceYears: null,
    requiredProfessionalTitles: [], duties: null, eventTitle: '公开招聘', publishedOn: null,
    applicationStartsOn: null, applicationEndsOn: null, dataQualityStatus: 'VERIFIED',
    supervisingDepartment: null, jobCategory: null, jobGrade: null, educationRequirementText: null,
    degreeRequirement: null, majorRequirementText: null, ageRequirementText: null, genderRequirement: null,
    candidateScope: null, otherRequirements: null, originalRequirementText: null, interviewRatio: null,
    professionalTestRequired: null, contactPhone: null, attachmentSourceUrl: 'https://example.gov.cn/jobs.xlsx',
    applicationStartsAt: null, applicationEndsAt: null, registrationUrl: null, qualificationReviewEndsOn: null,
    paymentEndsOn: null, admissionTicketStartsOn: null, admissionTicketEndsOn: null, writtenExamOn: null,
    writtenExamSubjects: [], graduateRule: null, overseasDegreeRule: null, experienceEvidenceRule: null,
    employmentStatement: null, interviewRule: null, ...overrides,
  }
}

describe('ScopeDecisionPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  /** 人工确认不能替证据作保：证据不齐时按钮不可点，并说明原因。 */
  it('does not let a reviewer include a job whose official evidence is incomplete', () => {
    render(<AppProviders><ScopeDecisionPanel match={match({ dataQualityStatus: 'NORMALIZED' })} /></AppProviders>)

    expect(screen.getByRole('button', { name: '纳入可信机会池' })).toBeDisabled()
    expect(screen.getByText(/人工确认不能替证据作保/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '不属于目标范围' })).toBeEnabled()
  })

  it('does not let a reviewer include a job whose employment identity is unknown', () => {
    render(<AppProviders><ScopeDecisionPanel match={match({ employmentIdentityConfirmed: false })} /></AppProviders>)

    expect(screen.getByRole('button', { name: '纳入可信机会池' })).toBeDisabled()
    expect(screen.getByText(/用工身份尚未由官网证据确认/)).toBeInTheDocument()
  })

  /** 请求带上复核人看到的那一版内容指纹；岗位在此之后变了，后端会拒绝。 */
  it('sends the fingerprint the reviewer saw and shows the job as trusted afterwards', async () => {
    const fetch = vi.fn().mockResolvedValue(json({
      jobId: '01992f09-0000-7000-8000-0000000000aa', decisionId: 'd-1', decision: 'INCLUDE', admitted: true,
      dataQualityStatus: 'VERIFIED', targetScopeStatus: 'INCLUDED',
      reasonCodes: ['TARGET_TECHNICAL_ROLE', 'HUMAN_SCOPE_INCLUDED'], reviewer: 'zhang', decidedAt: '2026-09-24T08:00:00Z',
    }))
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><ScopeDecisionPanel match={match()} /></AppProviders>)

    await userEvent.type(screen.getByLabelText('备注（可选）'), '信息中心岗')
    await userEvent.click(screen.getByRole('button', { name: '纳入可信机会池' }))

    expect(await screen.findByText('已纳入可信机会池')).toBeInTheDocument()
    const [path, init] = fetch.mock.calls[0]
    expect(path).toBe('/api/v1/job-admissions/01992f09-0000-7000-8000-0000000000aa/scope-decisions')
    expect(JSON.parse(init.body)).toEqual({ decision: 'INCLUDE', contentFingerprint: 'f'.repeat(64), note: '信息中心岗' })
  })

  it('explains that only reviewers can admit jobs when the account lacks the role', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ code: 'HTTP_403' }, 403)))
    render(<AppProviders><ScopeDecisionPanel match={match()} /></AppProviders>)

    await userEvent.click(screen.getByRole('button', { name: '纳入可信机会池' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('只有复核人账号')
  })

  it('shows a job a reviewer already admitted as trusted without offering the buttons again', () => {
    render(<AppProviders><ScopeDecisionPanel match={match({
      admissionReasons: ['TARGET_TECHNICAL_ROLE', 'HUMAN_SCOPE_INCLUDED'],
    })} /></AppProviders>)

    expect(screen.getByText('已纳入可信机会池')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '纳入可信机会池' })).not.toBeInTheDocument()
  })
})
