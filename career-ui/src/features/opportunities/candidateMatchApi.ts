import type { EligibilityStatus, PageResponse } from '../../api/contracts'
import { requestJson } from '../../api/http'

export type EmploymentType = 'ESTABLISHMENT' | 'QUOTA_OR_FILING' | 'PUBLIC_INSTITUTION_FORMAL' | 'UNIT_FORMAL' | 'SOE_FORMAL' | 'PERSONNEL_AGENCY' | 'LABOR_DISPATCH' | 'CONTRACT' | 'PROJECT_BASED' | 'UNKNOWN'
export type JobFamily = 'SOFTWARE' | 'DATA' | 'AI' | 'CYBERSECURITY' | 'INFORMATION_SYSTEMS' | 'DIGITALIZATION' | 'IT_OPERATIONS' | 'RESEARCH' | 'PRODUCT' | 'OTHER'
export type EducationLevel = 'UNKNOWN' | 'HIGH_SCHOOL' | 'ASSOCIATE' | 'BACHELOR' | 'MASTER' | 'DOCTORATE'
export type DataQualityStatus = 'RAW' | 'PARSED' | 'NORMALIZED' | 'REVIEW_REQUIRED' | 'VERIFIED' | 'REJECTED' | 'FAILED'

export type CandidateMatch = {
  jobId: string
  externalJobCode: string | null
  jobTitle: string
  organizationName: string
  location: string
  eligibilityStatus: EligibilityStatus
  fitScore: number
  coveragePercent: number
  employmentType: EmploymentType
  employmentIdentityConfirmed: boolean
  actualEmployer: string | null
  worksite: string | null
  employmentEvidence: string | null
  admissionReasons: string[]
  warnings: string[]
  sourceUrl: string
  jobContentFingerprint: string
  headcount: number
  jobFamily: JobFamily
  minimumEducation: EducationLevel
  exactMajors: string[]
  acceptedGraduationYears: number[]
  maximumAge: number | null
  ageReferenceDate: string | null
  minimumExperienceYears: number | null
  requiredProfessionalTitles: string[]
  duties: string | null
  eventTitle: string
  publishedOn: string | null
  applicationStartsOn: string | null
  applicationEndsOn: string | null
  dataQualityStatus: DataQualityStatus
  supervisingDepartment: string | null
  jobCategory: string | null
  jobGrade: string | null
  educationRequirementText: string | null
  degreeRequirement: string | null
  majorRequirementText: string | null
  ageRequirementText: string | null
  genderRequirement: string | null
  candidateScope: string | null
  otherRequirements: string | null
  originalRequirementText: string | null
  interviewRatio: string | null
  professionalTestRequired: boolean | null
  contactPhone: string | null
  attachmentSourceUrl: string
  applicationStartsAt: string | null
  applicationEndsAt: string | null
  registrationUrl: string | null
  qualificationReviewEndsOn: string | null
  paymentEndsOn: string | null
  admissionTicketStartsOn: string | null
  admissionTicketEndsOn: string | null
  writtenExamOn: string | null
  writtenExamSubjects: string[]
  graduateRule: string | null
  overseasDegreeRule: string | null
  experienceEvidenceRule: string | null
  employmentStatement: string | null
  interviewRule: string | null
  qualitySummary?: {
    verifiedFieldCount: number
    missingFields: string[]
    conflictFields: string[]
    evidenceReferences: Array<{
      fieldName: string
      factStatus: string
      sourceTitle: string | null
      sourceUrl: string | null
      locator: string | null
      excerpt: string | null
    }>
  }
}

export function listCandidateMatches(candidateId: string, page = 0) {
  const parameters = new URLSearchParams({ page: String(page), size: '20' })
  return requestJson<PageResponse<CandidateMatch>>(`/api/v1/candidates/${candidateId}/job-matches?${parameters}`)
}

export type ScopeDecision = 'INCLUDE' | 'EXCLUDE'

export type ScopeDecisionResponse = {
  jobId: string
  decisionId: string
  decision: ScopeDecision
  /** 这次决定之后它是否真的在可信机会池里。 */
  admitted: boolean
  dataQualityStatus: DataQualityStatus
  targetScopeStatus: 'INCLUDED' | 'EXCLUDED' | 'NEEDS_REVIEW'
  reasonCodes: string[]
  reviewer: string
  decidedAt: string
}

/**
 * 复核人确认岗位在不在目标范围内。带上看到的那一版内容指纹：
 * 岗位在此之后变了，后端返回 409，而不是把决定记到新内容上。
 */
export function decideScope(jobId: string, decision: ScopeDecision, contentFingerprint: string, note?: string) {
  return requestJson<ScopeDecisionResponse>(`/api/v1/job-admissions/${jobId}/scope-decisions`, {
    method: 'POST',
    body: JSON.stringify({ decision, contentFingerprint, note: note?.trim() || undefined }),
  })
}

/** 已经由复核人纳入可信机会池。 */
export function includedByReviewer(match: Pick<CandidateMatch, 'admissionReasons'>) {
  return match.admissionReasons.includes('HUMAN_SCOPE_INCLUDED')
}

/** 证据齐全、用工身份已知——只有这样的岗位才能被纳入。与后端的判断同源于数据质量。 */
export function canBeIncluded(match: Pick<CandidateMatch, 'dataQualityStatus' | 'employmentIdentityConfirmed'>) {
  return match.dataQualityStatus === 'VERIFIED' && match.employmentIdentityConfirmed
}
