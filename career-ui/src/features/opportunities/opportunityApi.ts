import type { EligibilityStatus, OpportunityTier, PageResponse } from '../../api/contracts'
import { requestJson } from '../../api/http'

export type AssessmentDimension = {
  type: string
  achievedPoints: number
  maximumPoints: number
  factStatus: 'EXPLICIT' | 'INFERRED' | 'UNKNOWN' | string
  reasonCode: string
  explanation: string
  evidenceIds: string[]
}

export type DecisionScore = { score: number; coveragePercent: number; dimensions: AssessmentDimension[] }

export type JobDecision = {
  decisionId: string
  candidateId: string
  jobId: string
  jobTitle: string
  organizationName: string
  location: string
  eligibilityStatus: EligibilityStatus
  tier: OpportunityTier
  recommendationStatus: string
  fit: DecisionScore
  stability: DecisionScore
  explanation: string
  warnings: string[]
  evidenceIds: string[]
  evaluatorVersion: string
  profileVersion: string
  jobContentFingerprint: string
  assessedAt: string
  disclaimer: string
}

export type DecisionPage = PageResponse<JobDecision> & { disclaimer: string }

export function listDecisions(candidateId: string, tier: OpportunityTier, page = 0) {
  const parameters = new URLSearchParams({ tier, page: String(page), size: '20' })
  if (tier === 'EXCLUDED') parameters.set('includeExcluded', 'true')
  return requestJson<DecisionPage>(`/api/v1/candidates/${candidateId}/job-decisions?${parameters}`)
}
