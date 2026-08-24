import { requestJson } from '../../api/http'
import type { JobLibrarySummary } from '../../api/contracts'

export type AcquisitionCoverage = {
  sourceId: string; year: number; status: string; discoveredCount: number; fetchedCount: number; parsedCount: number;
  targetJobCount: number; completionBasis: string | null; completedAt: string | null; updatedAt: string;
  supportsAbsenceConclusion: boolean; listingPageCount: number; filteredCount: number; failedCount: number;
  earliestPublishedOn: string | null; latestPublishedOn: string | null; stopReason: string | null
}

export type AcquisitionCheckpoint = {
  sourceId: string; checkpoint: string; status: string; evidence: string | null; verifiedAt: string
}

export type AcquisitionSource = {
  id: string; code: string; name: string; entryUri: string; sourceType: string; region: string; crawlMode: string;
  enabled: boolean; cronExpression: string; timeZone: string; lastSuccessAt: string | null; lastFailureAt: string | null;
  nextDueAt: string | null; consecutiveFailureCount: number;
  connectionStatus: 'CONNECTED' | 'PARTIAL' | 'FAILED' | 'NOT_CONNECTED';
  coverage: AcquisitionCoverage[]; checkpoints: AcquisitionCheckpoint[]; historicalFailureCount: number
}

export type AcquisitionRun = {
  id: string; sourceId: string; trigger: string; status: 'RUNNING' | 'SUCCEEDED' | 'PARTIALLY_SUCCEEDED' | 'FAILED' | 'SKIPPED_LOCKED';
  startedAt: string; completedAt: string | null; discoveredCount: number; fetchedCount: number; unchangedCount: number;
  addedCount: number; updatedCount: number; deactivatedCount: number; failedCount: number; errorCode: string | null; errorMessage: string | null
}

export type ExtractionResult = { id: string; status: string; reused: boolean; evidenceId: string; reviewId: string | null; proposal: ReviewProposal | null; errorCode: string | null; errorMessage: string | null }
export type ExtractedFact<T = unknown> = { value: T | null; factStatus: string; confidence: number; evidenceFragmentIds: string[]; interpretation: string | null }
export type ReviewProposal = {
  schemaVersion: string
  source: { evidenceId: string; sourceUrl: string; sourceTitle: string }
  organization: { name: string; organizationType: ExtractedFact<string> }
  recruitmentEvent: { title: string; recruitmentYear: number; eventType: string; publishedOn: ExtractedFact<string>; applicationStartsOn: ExtractedFact<string>; applicationEndsOn: ExtractedFact<string> }
  jobs: Record<string, unknown>[]
  warnings: string[]
  confidence: number
  completeSnapshot: boolean
}
export type ReviewItem = { id: string; extractionRunId: string; status: string; version: number; proposal: ReviewProposal; issues: { reasonCode?: string; message?: string }[]; actions: unknown[]; createdAt: string; resolvedAt: string | null }
export type ReviewPage = { content: ReviewItem[]; page: number; size: number; totalElements: number }

export const listSources = () => requestJson<AcquisitionSource[]>('/api/acquisition/sources')
export const getJobLibrarySummary = () => requestJson<JobLibrarySummary>('/api/v1/job-library/summary')
export const findRun = (id: string) => requestJson<AcquisitionRun>(`/api/acquisition/runs/${id}`)

export async function triggerSource(sourceId: string) {
  let run = await requestJson<AcquisitionRun>(`/api/acquisition/sources/${sourceId}/runs`, { method: 'POST' })
  for (let attempt = 0; run.status === 'RUNNING' && attempt < 20; attempt += 1) {
    await new Promise(resolve => setTimeout(resolve, 750))
    run = await findRun(run.id)
  }
  return run
}

export function importExcel(form: FormData) { return requestJson<Record<string, unknown>>('/api/v1/imports/excel', { method: 'POST', body: form }) }

export function extractDocument(file: File, sourceUrl: string, sourceTitle: string) {
  const form = new FormData()
  form.set('document', file)
  form.set('metadata', new Blob([JSON.stringify({ sourceUrl, sourceTitle, capturedAt: new Date().toISOString(), requireModel: false })], { type: 'application/json' }))
  return requestJson<ExtractionResult>('/api/v1/extractions', { method: 'POST', body: form })
}

export const listPendingReviews = () => requestJson<ReviewPage>('/api/v1/reviews?status=PENDING&page=0&size=20')
export function applyReviewAction(reviewId: string, body: { decision: string; expectedVersion: number; correctedPayload: ReviewProposal | null; note: string | null }) {
  return requestJson<unknown>(`/api/v1/reviews/${reviewId}/actions`, { method: 'POST', body: JSON.stringify(body) })
}
