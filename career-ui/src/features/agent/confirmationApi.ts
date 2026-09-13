import { requestJson } from '../../api/http'
import type { EvidenceStrength } from '../personal/personalTypes'

export type ConfirmationResult =
  | 'RECORDED'
  | 'RECORDED_RECOMPUTE_DEFERRED'
  | 'ALREADY_RECORDED'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'NO_CHANGE_NEEDED'
  | 'CHANGE_REQUIRES_ACKNOWLEDGEMENT'
  | 'PROFILE_VERSION_CHANGED'
  | 'REQUIRES_DOCUMENT'

export type EligibilityStatus =
  | 'ELIGIBLE' | 'CONDITIONAL' | 'NEEDS_CONFIRMATION' | 'CONFLICTING_EVIDENCE' | 'INELIGIBLE'

export type AffectedJob = {
  jobId: string
  title: string
  organizationName: string
  previousStatus: EligibilityStatus
  currentStatus: EligibilityStatus
  reasons: string[]
  deepLink: string
}

/**
 * 重算出来的变化。
 *
 * `available` 为 false 时三个计数都是 null——没有可靠的对照基线时不编造"零变化"，
 * 那会读成"确认没用"，而实际是"算不出来"。
 */
export type DecisionChangeSummary = {
  previousProfileVersion: string
  currentProfileVersion: string
  available: boolean
  message: string | null
  newlyEligibleCount: number | null
  resolvedUncertaintyCount: number | null
  newlyIneligibleCount: number | null
  affectedJobs: AffectedJob[]
}

export type ConfirmationResponse = {
  result: ConfirmationResult
  /** 恒为 SELF_REPORTED：对话里的确认是本人声明，不是官方核实。 */
  evidenceStrength: EvidenceStrength
  profileVersionBefore: string
  profileVersionAfter: string
  message: string
  pendingChange: { factKey: string; from: string; to: string } | null
  changes: DecisionChangeSummary | null
}

export type ConfirmationRequest = {
  factKey: string
  value: string
  sessionId: string
  idempotencyKey: string
  acknowledgedChange: boolean
}

export function recordConfirmation(candidateId: string, asOf: string, request: ConfirmationRequest) {
  return requestJson<ConfirmationResponse>(
    `/api/v1/candidates/${candidateId}/profile-confirmations?asOf=${asOf}`,
    { method: 'POST', body: JSON.stringify(request) },
  )
}

/** 每个待确认问题对应的可选答案。取值是后端的封闭枚举，前端不自造。 */
export const answerOptions: Record<string, { value: string; label: string }[]> = {
  POLITICAL_AFFILIATION: [
    { value: 'CPC_MEMBER', label: '中共党员' },
    { value: 'CPC_PROBATIONARY', label: '中共预备党员' },
    { value: 'NON_MEMBER', label: '群众／其他' },
  ],
  GENDER: [
    { value: 'FEMALE', label: '女' },
    { value: 'MALE', label: '男' },
  ],
  EMPLOYER_SETTLEMENT_AT_APPLICATION: [
    { value: 'DECLARED_MET', label: '报名时尚未落实工作单位' },
    { value: 'DECLARED_NOT_MET', label: '报名时已落实工作单位' },
  ],
  SOCIAL_INSURANCE_AT_APPLICATION: [
    { value: 'DECLARED_MET', label: '报名时无社保缴纳记录' },
    { value: 'DECLARED_NOT_MET', label: '报名时有社保缴纳记录' },
  ],
}

export const factLabels: Record<string, string> = {
  POLITICAL_AFFILIATION: '政治面貌',
  GENDER: '性别',
  EMPLOYER_SETTLEMENT_AT_APPLICATION: '报名时是否已落实工作单位',
  SOCIAL_INSURANCE_AT_APPLICATION: '报名时社保缴纳状态',
}

export const statusLabels: Record<EligibilityStatus, string> = {
  ELIGIBLE: '可报',
  CONDITIONAL: '条件可报',
  NEEDS_CONFIRMATION: '待确认',
  CONFLICTING_EVIDENCE: '证据冲突',
  INELIGIBLE: '不可报',
}
