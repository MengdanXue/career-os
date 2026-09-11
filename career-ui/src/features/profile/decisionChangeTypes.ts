export type EligibilityStatus = 'ELIGIBLE' | 'CONDITIONAL' | 'NEEDS_CONFIRMATION' | 'CONFLICTING_EVIDENCE' | 'INELIGIBLE'

export interface DecisionChangeJob {
  jobId: string
  title: string
  organizationName: string
  previousStatus: EligibilityStatus
  currentStatus: EligibilityStatus
  reasons: string[]
  deepLink: string
}

export interface DecisionChangeSummary {
  candidateId: string
  previousProfileVersion: string
  currentProfileVersion: string
  asOf: string
  available: boolean
  message: string | null
  newlyEligibleCount: number | null
  resolvedUncertaintyCount: number | null
  newlyIneligibleCount: number | null
  affectedJobs: DecisionChangeJob[]
}
