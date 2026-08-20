import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { CandidateMatch } from './candidateMatchApi'
import { OfficialJobDetail } from './OfficialJobDetail'

function match(overrides: Partial<CandidateMatch> = {}): CandidateMatch {
  return {
    jobId: crypto.randomUUID(), externalJobCode: null, jobTitle: '技术岗', organizationName: '测试单位',
    location: '杭州', eligibilityStatus: 'ELIGIBLE', fitScore: 60, coveragePercent: 50,
    employmentType: 'UNKNOWN', employmentIdentityConfirmed: false, admissionReasons: [], warnings: [],
    sourceUrl: 'https://example.gov.cn/notice', jobContentFingerprint: 'a'.repeat(64), headcount: 1,
    jobFamily: 'SOFTWARE', minimumEducation: 'DOCTORATE', exactMajors: [], acceptedGraduationYears: [],
    maximumAge: null, ageReferenceDate: null, minimumExperienceYears: null,
    requiredProfessionalTitles: [], duties: null, eventTitle: '公开招聘', publishedOn: null,
    applicationStartsOn: null, applicationEndsOn: null, ...overrides,
  }
}

describe('OfficialJobDetail', () => {
  it.each([
    ['SOFTWARE', '软件研发'], ['AI', '人工智能'], ['CYBERSECURITY', '网络与安全'],
    ['IT_OPERATIONS', 'IT 运维'], ['RESEARCH', '科研'], ['PRODUCT', '产品'],
  ] as const)('maps backend job family %s to %s', (jobFamily, label) => {
    render(<OfficialJobDetail match={match({ jobFamily })} onClose={vi.fn()} />)
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.getByText('博士研究生')).toBeInTheDocument()
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
})
