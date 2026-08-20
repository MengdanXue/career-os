import { requestJson } from '../../api/http'

export type WorkbenchSummary = {
  candidateId: string
  generatedAt: string
  tierCounts: { t1: number; t2: number; t3: number; excluded: number }
  deadlines: { jobId: string; jobTitle: string; organizationName: string; location: string; tier: string; deadline: string; daysRemaining: number }[]
  changes: { available: boolean; message: string | null; items: { id: string; changeType: string; sourceUri: string; jobDeltaSummary: Record<string, unknown>; occurredAt: string }[] }
  sources: { available: boolean; message: string | null; healthy: number; enabled: number; issues: { id: string; name: string; consecutiveFailureCount: number; lastFailureAt: string | null }[] }
  reviews: { available: boolean; message: string | null; pending: number }
}

export function getWorkbenchSummary(candidateId: string) {
  return requestJson<WorkbenchSummary>(`/api/v1/candidates/${candidateId}/workbench-summary`)
}
