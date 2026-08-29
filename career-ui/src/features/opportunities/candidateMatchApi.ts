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
