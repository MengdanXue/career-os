import { queryKeys, requestJson } from '../../api/http'
import type { CandidateProfile, CandidateProfileUpdate } from './profileSchema'

export function listCandidates() {
  return requestJson<CandidateProfile[]>('/api/v1/candidates')
}

export function updateCandidate(candidateId: string, profile: CandidateProfileUpdate) {
  return requestJson<CandidateProfile>(`/api/v1/candidates/${candidateId}`, {
    method: 'PUT',
    body: JSON.stringify(profile),
  })
}

export const profileKeys = {
  list: ['candidates'] as const,
  detail: queryKeys.candidate,
}
