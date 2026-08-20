import type { EligibilityStatus, PageResponse } from '../../api/contracts'
import { requestJson } from '../../api/http'

export type CandidateMatch = {
  jobId: string
  jobTitle: string
  organizationName: string
  location: string
  eligibilityStatus: EligibilityStatus
  fitScore: number
  coveragePercent: number
  employmentType: string
  employmentIdentityConfirmed: boolean
  admissionReasons: string[]
  warnings: string[]
  sourceUrl: string
  jobContentFingerprint: string
}

export function listCandidateMatches(candidateId: string, page = 0) {
  const parameters = new URLSearchParams({ page: String(page), size: '20' })
  return requestJson<PageResponse<CandidateMatch>>(`/api/v1/candidates/${candidateId}/job-matches?${parameters}`)
}
