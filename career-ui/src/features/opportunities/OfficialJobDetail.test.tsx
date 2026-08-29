import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { CandidateMatch } from './candidateMatchApi'
import { OfficialJobDetail } from './OfficialJobDetail'

function match(overrides: Partial<CandidateMatch> = {}): CandidateMatch {
  return {
    jobId: crypto.randomUUID(), externalJobCode: null, jobTitle: '技术岗', organizationName: '测试单位',
    location: '杭州', eligibilityStatus: 'ELIGIBLE', fitScore: 60, coveragePercent: 50,
    employmentType: 'UNKNOWN', employmentIdentityConfirmed: false, admissionReasons: [], warnings: [],
    actualEmployer: null, worksite: null, employmentEvidence: null,
    sourceUrl: 'https://example.gov.cn/notice', jobContentFingerprint: 'a'.repeat(64), headcount: 1,
    jobFamily: 'SOFTWARE', minimumEducation: 'DOCTORATE', exactMajors: [], acceptedGraduationYears: [],
    maximumAge: null, ageReferenceDate: null, minimumExperienceYears: null,
    requiredProfessionalTitles: [], duties: null, eventTitle: '公开招聘', publishedOn: null,
    applicationStartsOn: null, applicationEndsOn: null,
    dataQualityStatus: 'VERIFIED', supervisingDepartment: null, jobCategory: null, jobGrade: null,
    educationRequirementText: null, degreeRequirement: null, majorRequirementText: null,
    ageRequirementText: null, genderRequirement: null, candidateScope: null, otherRequirements: null,
    originalRequirementText: null, interviewRatio: null, professionalTestRequired: null,
    contactPhone: null, attachmentSourceUrl: 'https://example.gov.cn/jobs.xlsx',
    applicationStartsAt: null, applicationEndsAt: null, registrationUrl: null,
    qualificationReviewEndsOn: null, paymentEndsOn: null, admissionTicketStartsOn: null,
    admissionTicketEndsOn: null, writtenExamOn: null, writtenExamSubjects: [], graduateRule: null,
    overseasDegreeRule: null, experienceEvidenceRule: null, employmentStatement: null,
    interviewRule: null, ...overrides,
  }
}

describe('OfficialJobDetail', () => {
  it.each([
    ['SOFTWARE', '软件研发'], ['AI', '人工智能'], ['CYBERSECURITY', '网络与安全'],
    ['IT_OPERATIONS', 'IT 运维'], ['RESEARCH', '科研'], ['PRODUCT', '产品'],
  ] as const)('maps backend job family %s to %s', (jobFamily, label) => {
    render(<OfficialJobDetail match={match({ jobFamily })} onClose={vi.fn()} />)
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.getByText('系统归一化：博士研究生')).toBeInTheDocument()
  })

  it.each([
    ['2026-07-10', '2026-07-20', '2026-07-10 至 2026-07-20'],
    ['2026-07-10', null, '自 2026-07-10 起'],
    [null, '2026-07-20', '截至 2026-07-20'],
    [null, null, '公告未明确'],
  ] as const)('labels partial application periods without losing meaning', (startsOn, endsOn, expected) => {
    render(<OfficialJobDetail match={match({ applicationStartsOn: startsOn, applicationEndsOn: endsOn })} onClose={vi.fn()} />)
    expect(screen.getAllByText(expected).length).toBeGreaterThan(0)
  })

  it('shows the complete official position, application process and evidence without leaving the app', () => {
    render(<OfficialJobDetail match={match({
      employmentType: 'PUBLIC_INSTITUTION_FORMAL', employmentIdentityConfirmed: true,
      actualEmployer: '杭州市西溪医院', worksite: '西溪院区',
      employmentEvidence: '公告原文：录用后由杭州市西溪医院直接聘用',
      jobCategory: '专业技术', jobGrade: '十级以下', educationRequirementText: '硕士研究生及以上',
      degreeRequirement: '硕士及以上', majorRequirementText: '计算机科学与技术、软件工程',
      ageRequirementText: '38周岁及以下', genderRequirement: '不限', candidateScope: '不限',
      otherRequirements: '需进行专业知识测试', interviewRatio: '1:4', professionalTestRequired: true,
      contactPhone: '0571-12345678', applicationStartsAt: '2026-03-19T01:00:00Z',
      applicationEndsAt: '2026-03-25T08:00:00Z', registrationUrl: 'https://qssy.zjks.com',
      qualificationReviewEndsOn: '2026-03-26T09:00:00Z', paymentEndsOn: '2026-03-27T16:00:00Z',
      writtenExamOn: '2026-04-25', writtenExamSubjects: ['职业能力倾向测验', '综合应用能力'],
      graduateRule: '2024年、2025年、2026年毕业生可报考', overseasDegreeRule: '境外学历须完成认证',
      experienceEvidenceRule: '工作经历须提供证明', employmentStatement: '公示后签订聘用合同',
      interviewRule: '按笔试成绩确定面试人选',
      qualitySummary: {
        verifiedFieldCount: 8, missingFields: [], conflictFields: [],
        evidenceReferences: [{
          fieldName: 'majorRequirementText', factStatus: 'EXPLICIT', sourceTitle: '招聘计划表',
          sourceUrl: 'https://example.gov.cn/jobs.xlsx',
          locator: '{"sheetName":"岗位表","rowNumber":12,"columnName":"专业要求"}',
          excerpt: '计算机科学与技术、软件工程',
        }],
      },
    })} onClose={vi.fn()} />)

    expect(screen.getByText('事业单位正式聘用')).toBeInTheDocument()
    expect(screen.getByText('杭州市西溪医院')).toBeInTheDocument()
    expect(screen.getByText('西溪院区')).toBeInTheDocument()
    expect(screen.getByText('公告原文：录用后由杭州市西溪医院直接聘用')).toBeInTheDocument()
    expect(screen.getByText('官网关键事实已核实')).toBeInTheDocument()
    expect(screen.getAllByText('计算机科学与技术、软件工程')).toHaveLength(2)
    expect(screen.getByText(/2026-03-19 09:00/)).toBeInTheDocument()
    expect(screen.getByText(/职业能力倾向测验、综合应用能力/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开报名入口' })).toHaveAttribute('href', 'https://qssy.zjks.com')
    expect(screen.getByRole('link', { name: '查看岗位附件' })).toHaveAttribute('href', 'https://example.gov.cn/jobs.xlsx')
    expect(screen.getByText('字段证据链')).toBeInTheDocument()
    expect(screen.getByText(/岗位表.*rowNumber.*12/)).toBeInTheDocument()
    expect(screen.queryByText('岗位表未明确')).not.toBeInTheDocument()
  })
})
