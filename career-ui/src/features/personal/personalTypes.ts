export type EvidenceStrength = 'NONE' | 'SELF_REPORTED' | 'DOCUMENTED' | 'VERIFIED'
export type PersonalActionKind = 'CURRENT_JOB_DEADLINE' | 'CANDIDATE_EVIDENCE' | 'TARGET_JOB_CHANGE' | 'APPLICATION_NEXT_STEP' | 'PREPARATION_TIMELINE'

export type PersonalAction = {
  id: string
  kind: PersonalActionKind
  priority: number
  title: string
  reason: string
  affectedObjectCount: number
  dueOn: string | null
  evidenceStrength: EvidenceStrength
  deepLink: string
}

export type PersonalActions = {
  candidateId: string
  asOf: string
  available: boolean
  message: string | null
  items: PersonalAction[]
}

export type CandidateEvidenceTask = {
  code: string
  kind: 'EMPLOYMENT' | 'GRADUATION' | 'CREDENTIAL' | 'SKILL' | 'RESEARCH' | 'POLITICAL_AFFILIATION' | 'PROFESSIONAL_TITLE'
  factKey: string
  title: string
  reason: string
  affectedJobCount: number
  evidenceStrength: EvidenceStrength
  deepLink: string
}

export type CandidateEvidenceTasks = {
  candidateId: string
  asOf: string
  available: boolean
  message: string | null
  items: CandidateEvidenceTask[]
}
