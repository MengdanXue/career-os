import { queryKeys, requestJson } from '../../api/http'
import { candidateFactKeys, type CandidateProfile, type CandidateProfileFacts, type CandidateProfileUpdate } from './profileSchema'
import type { CandidateEvidenceTasks } from '../personal/personalTypes'
import type { DecisionChangeSummary } from './decisionChangeTypes'

export function listCandidates() {
  return requestJson<CandidateProfile[]>('/api/v1/candidates')
}

export function updateCandidate(candidateId: string, profile: CandidateProfileUpdate) {
  return requestJson<CandidateProfile>(`/api/v1/candidates/${candidateId}`, {
    method: 'PUT',
    body: JSON.stringify(profile),
  })
}

export function getCandidateFacts(candidateId: string) {
  return requestJson<CandidateProfileFacts>(`/api/v1/candidates/${candidateId}/facts`)
}

export function confirmCandidateFacts(candidateId: string) {
  return requestJson<CandidateProfileFacts>(`/api/v1/candidates/${candidateId}/facts/confirm`, {
    method: 'POST',
    body: JSON.stringify({ factKeys: candidateFactKeys }),
  })
}

export function getCandidateEvidenceTasks(candidateId: string) {
  return requestJson<CandidateEvidenceTasks>(`/api/v1/candidates/${candidateId}/evidence-tasks`)
}

export function recomputeDecisionChanges(candidateId: string, previousProfileVersion: string, asOf: string) {
  return requestJson<DecisionChangeSummary>(
    `/api/v1/candidates/${candidateId}/decision-change-summaries/${encodeURIComponent(previousProfileVersion)}?asOf=${encodeURIComponent(asOf)}`,
    { method: 'POST' },
  )
}

export const profileKeys = {
  list: ['candidates'] as const,
  detail: queryKeys.candidate,
  facts: (candidateId: string) => ['candidate-facts', candidateId] as const,
  evidenceTasks: (candidateId: string) => ['candidate-evidence-tasks', candidateId] as const,
}
