import { queryKeys, requestJson } from '../../api/http'

export type Scenario = { code: string; label: string; description: string; effectiveFrom: string | null; current: boolean }
export type RepresentativeJob = { jobId: string; organizationName: string; title: string; year: number; sourceUrl: string; evidenceComplete: boolean }
export type Route = {
  code: string; label: string; priorityScore: number; priorityLabel: string; historicalJobCount: number
  eventCount: number; formalJobCount: number; organizations: string[]; jobFamilies: string[]
  applicableScenarios: string[]; advantages: string[]; risks: string[]; preparationFocus: string[]
  representativeJobs: RepresentativeJob[]; evidenceStrength: 'STRONG' | 'MODERATE' | 'LIMITED' | 'INSUFFICIENT'
}
export type CareerPlan = {
  candidateId: string; targetYear: number; asOf: string
  candidateSnapshot: { displayName: string; birthDate: string; gender: string; profileVersion: string; educationSummary: string; expectedMasterGraduationYear: number | null }
  currentScenario: Scenario; futureScenarios: Scenario[]; recommendedRoutes: Route[]
  ageWindows: { year: number; referenceDate: string; maximumAge: number; candidateAge: number; eligible: boolean; label: string; basis: string; conditional: boolean }[]
  recruitmentWindows: { month: number; eventCount: number; label: string; basis: string }[]
  examPatterns: { subject: string; eventCount: number }[]
  historicalSummary: { year: number; jobCount: number; eventCount: number; formalJobCount: number; coverageComplete: boolean }[]
  qualificationRisks: { code: string; severity: 'HIGH' | 'MEDIUM' | 'LOW'; title: string; detail: string }[]
  actionTimeline: { startsOn: string; endsOn: string; title: string; detail: string; status: string }[]
  dataCoverage: { complete: boolean; sourceYearCount: number; completeSourceYearCount: number; incompleteSourceYears: string[]; warnings: string[]; loadedAt: string }
  generatedAt: string; algorithmVersion: string
}

export function getCareerPlan(candidateId: string, targetYear: number) {
  return requestJson<CareerPlan>(`/api/v1/candidates/${candidateId}/career-plan?targetYear=${targetYear}`)
}

export const planningKeys = { plan: queryKeys.careerPlan }
