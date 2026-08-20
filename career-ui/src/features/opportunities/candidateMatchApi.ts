import type { EligibilityStatus, PageResponse } from '../../api/contracts'
import { requestJson } from '../../api/http'

export type EmploymentType = 'ESTABLISHMENT' | 'PERSONNEL_AGENCY' | 'LABOR_DISPATCH' | 'CONTRACT' | 'PROJECT_BASED' | 'UNKNOWN'
export type JobFamily = 'SOFTWARE' | 'DATA' | 'AI' | 'CYBERSECURITY' | 'INFORMATION_SYSTEMS' | 'DIGITALIZATION' | 'IT_OPERATIONS' | 'RESEARCH' | 'PRODUCT' | 'OTHER'
export type EducationLevel = 'UNKNOWN' | 'HIGH_SCHOOL' | 'ASSOCIATE' | 'BACHELOR' | 'MASTER' | 'DOCTORATE'

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
}

export function listCandidateMatches(candidateId: string, page = 0) {
  const parameters = new URLSearchParams({ page: String(page), size: '20' })
  return requestJson<PageResponse<CandidateMatch>>(`/api/v1/candidates/${candidateId}/job-matches?${parameters}`)
}
