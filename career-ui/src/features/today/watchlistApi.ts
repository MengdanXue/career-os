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
  /** 还没有比对基准。不是"无变化"——是从来没比过，下一次变化也发现不了。 */
  baselineMissing: boolean
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

/**
 * 关注一个岗位，并把用户此刻屏幕上的结论记为比对基准。
 *
 * <p>不带基准的话，第一次变化——也就是最该看到的那次——发现不了。
 */
export function watchJob(
  candidateId: string, jobId: string, seenStatus: EligibilityStatus, seenEvaluatorVersion: string | null,
) {
  return requestJson<{ jobId: string; at: string | null }>(
    `/api/v1/candidates/${candidateId}/watched-jobs/${jobId}`,
    { method: 'PUT', body: JSON.stringify({ seenStatus, seenEvaluatorVersion }) },
  )
}

export function unwatchJob(candidateId: string, jobId: string) {
  return requestJson<void>(`/api/v1/candidates/${candidateId}/watched-jobs/${jobId}`, { method: 'DELETE' })
}
