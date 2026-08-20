export type ProblemDetail = {
  type?: string
  title?: string
  status?: number
  detail?: string
  code?: string
}

export type EligibilityStatus = 'ELIGIBLE' | 'LIKELY_ELIGIBLE' | 'UNCERTAIN' | 'LIKELY_INELIGIBLE' | 'INELIGIBLE' | 'UNKNOWN' | 'CONDITIONAL'
export type OpportunityTier = 'T1' | 'T2' | 'T3' | 'REVIEW' | 'EXCLUDED'

export type PageResponse<T> = {
  items: T[]
  page: number
  size: number
  total: number
}

export type JobLibrarySummary = {
  total: number
  raw: number
  parsed: number
  normalized: number
  reviewRequired: number
  verified: number
  rejected: number
  failed: number
  included: number
  excluded: number
  needsReview: number
  opportunityReady: number
}
