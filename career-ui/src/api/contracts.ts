export type ProblemDetail = {
  type?: string
  title?: string
  status?: number
  detail?: string
  code?: string
}

export type EligibilityStatus = 'ELIGIBLE' | 'INELIGIBLE' | 'UNKNOWN' | 'CONDITIONAL'
export type OpportunityTier = 'T1' | 'T2' | 'T3' | 'REVIEW' | 'EXCLUDED'

export type PageResponse<T> = {
  items: T[]
  page: number
  size: number
  total: number
}
