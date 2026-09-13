import { requestJson } from '../../api/http'
import type { EligibilityStatus } from '../agent/confirmationApi'

export type WatchedJob = {
  jobPostingId: string
  jobTitle: string | null
  organizationName: string | null
  /** 用户上次看到的结论。为 null 表示还没看过——那是第一次看到，不是变化。 */
  lastSeenStatus: EligibilityStatus | null
  /** 当前结论。为 null 表示这次读不到，不是"没变化"。 */
  currentStatus: EligibilityStatus | null
  changedSinceLastSeen: boolean
  applicationEndsOn: string | null
  applicationClosed: boolean
  evaluatorVersion: string | null
  deepLink: string
}

export type Watchlist = {
  candidateId: string
  asOf: string
  items: WatchedJob[]
}

export function getWatchlist(candidateId: string, asOf: string) {
  return requestJson<Watchlist>(
    `/api/v1/candidates/${candidateId}/watched-jobs?asOf=${encodeURIComponent(asOf)}`,
  )
}

/**
 * 把岗位标记为已看过。
 *
 * <p>要带上用户屏幕上看到的那个结论，而不是让服务端重新算一遍：中间若又变了，
 * 用当前值推进会把那次变化一起吞掉。
 */
export function acknowledgeWatchedJob(
  candidateId: string, jobId: string, seenStatus: EligibilityStatus, seenEvaluatorVersion: string | null,
) {
  return requestJson<{ jobId: string; at: string | null }>(
    `/api/v1/candidates/${candidateId}/watched-jobs/${jobId}/acknowledgements`,
    { method: 'POST', body: JSON.stringify({ seenStatus, seenEvaluatorVersion }) },
  )
}

export function unwatchJob(candidateId: string, jobId: string) {
  return requestJson<void>(`/api/v1/candidates/${candidateId}/watched-jobs/${jobId}`, { method: 'DELETE' })
}
