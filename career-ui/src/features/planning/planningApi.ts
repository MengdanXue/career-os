import { queryKeys, requestJson } from '../../api/http'

export type Scenario = { code: string; label: string; description: string; effectiveFrom: string | null; current: boolean }
export type QualificationOutcome = 'ELIGIBLE' | 'CONDITIONALLY_ELIGIBLE' | 'UNCERTAIN' | 'INELIGIBLE'
export type GraduateTrackSummary = { code: string; label: string; detail: string; outcome: QualificationOutcome }
export type JobScenarioOutcome = { scenarioCode: string; outcome: QualificationOutcome; reasons: string[] }
export type RepresentativeJob = { jobId: string; organizationName: string; title: string; year: number; sourceUrl: string; evidenceComplete: boolean; scenarioOutcomes: JobScenarioOutcome[]; historicalActual: JobScenarioOutcome; targetYearAnalog: JobScenarioOutcome }
export type ScenarioBreakdown = { scenarioCode: string; eligible: number; conditionallyEligible: number; uncertain: number; ineligible: number; notes: string[] }
export type ScoreComponent = { code: string; label: string; score: number; weight: number; basis: string; evidenceBacked: boolean }
export type RouteRankingState = 'RANKED' | 'LIMITED' | 'NOT_COVERED' | 'NO_TARGET_RECORDS' | 'DATA_FAILURE'
export type Route = {
  code: string; label: string; priorityScore: number | null; priorityLabel: string; historicalJobCount: number
  eventCount: number; formalJobCount: number; organizations: string[]; jobFamilies: string[]
  applicableScenarios: string[]; advantages: string[]; risks: string[]; preparationFocus: string[]
  representativeJobs: RepresentativeJob[]; scenarioBreakdowns: ScenarioBreakdown[]; scoreComponents: ScoreComponent[]
  evidenceStrength: 'STRONG' | 'MODERATE' | 'LIMITED' | 'INSUFFICIENT'
  rankingState: RouteRankingState; rankingReason: string
}
export type ExamPattern = { subject: string; eventCount: number }
export type ExamSummary = {
  totalEvents: number
  writtenExamConfirmed: number; writtenExamNotRequired: number; writtenExamNotPublished: number; writtenExamNotCollected: number; writtenExamParseFailed: number; writtenExamReviewRequired: number; writtenExamUnknown: number
  professionalTestConfirmed: number; professionalTestNotRequired: number; professionalTestNotPublished: number; professionalTestNotCollected: number; professionalTestParseFailed: number; professionalTestReviewRequired: number; professionalTestUnknown: number
  interviewConfirmed: number; interviewNotRequired: number; interviewNotPublished: number; interviewNotCollected: number; interviewParseFailed: number; interviewReviewRequired: number; interviewUnknown: number
  subjects: ExamPattern[]; interviewMethods: ExamPattern[]
  applicationToWrittenExamSamples: number; averageApplicationToWrittenExamDays: number | null
}
export type ConfiguredCoverage = { complete: boolean; sourceYearCount: number; completeSourceYearCount: number; gaps: string[] }
export type RouteCoverage = { routeCode: string; targetCount: number; connected: number; partial: number; failed: number; notConnected: number; marketComplete: boolean }
export type TargetMarketCoverage = { targetCount: number; connected: number; partial: number; failed: number; notConnected: number; routes: RouteCoverage[] }
export type AnalysisCoverage = { sourceCount: number; eventCount: number; jobCount: number; evidenceCompleteJobs: number; loadedAt: string }
export type CareerPlan = {
  candidateId: string; targetYear: number; asOf: string
  candidateSnapshot: { displayName: string; birthDate: string; gender: string; profileVersion: string; educationSummary: string; expectedMasterGraduationYear: number | null }
  currentScenario: Scenario; futureScenarios: Scenario[]; graduateTrack: GraduateTrackSummary; recommendedRoutes: Route[]
  ageWindows: { year: number; referenceDate: string; maximumAge: number; candidateAge: number; eligible: boolean; label: string; basis: string; conditional: boolean }[]
  recruitmentWindows: { month: number; eventCount: number; label: string; basis: string }[]
  examPatterns: ExamPattern[]; examSummary: ExamSummary
  processWindows: { stage: 'NOTICE' | 'APPLICATION_START' | 'WRITTEN_EXAM' | 'INTERVIEW'; month: number; eventCount: number }[]
  historicalSummary: { year: number; jobCount: number; eventCount: number; formalJobCount: number; coverageComplete: boolean }[]
  qualificationRisks: { code: string; severity: 'HIGH' | 'MEDIUM' | 'LOW'; title: string; detail: string }[]
  actionTimeline: { startsOn: string; endsOn: string; title: string; detail: string; status: string }[]
  dataCoverage: { complete: boolean; sourceYearCount: number; completeSourceYearCount: number; incompleteSourceYears: string[]; warnings: string[]; failedSections: string[]; loadedAt: string }
  configuredCoverage: ConfiguredCoverage; targetMarketCoverage: TargetMarketCoverage; analysisCoverage: AnalysisCoverage
  generatedAt: string; algorithmVersion: string
}

export function getCareerPlan(candidateId: string, targetYear: number) {
  return requestJson<CareerPlan>(`/api/v1/candidates/${candidateId}/career-plan?targetYear=${targetYear}`)
}

export type PlanningJob = {
  id: string; recruitmentEventId: string; organizationId: string; externalJobCode: string | null
  title: string; jobFamily: string; employmentType: string; location: string | null; headcount: number
  minimumEducation: string; exactMajors: string[]; acceptedGraduationYears: number[]
  maximumAge: number | null; ageReferenceDate: string | null; minimumExperienceYears: number | null
  requiredProfessionalTitles: string[]; duties: string | null; sourceUrl: string
  supervisingDepartment: string | null; jobCategory: string | null; jobGrade: string | null
  educationRequirementText: string | null; degreeRequirement: string | null; majorRequirementText: string | null
  ageRequirementText: string | null; genderRequirement: string | null; candidateScope: string | null
  otherRequirements: string | null; originalRequirementText: string | null; interviewRatio: string | null
  professionalTestRequired: boolean | null; contactPhone: string | null
}

export type PlanningEvent = {
  id: string; title: string; recruitmentYear: number; publishedOn: string | null
  applicationStartsOn: string | null; applicationEndsOn: string | null
  applicationStartsAt?: string | null; applicationEndsAt?: string | null
  sourceUrl: string; registrationUrl: string | null; writtenExamOn: string | null
  writtenExamSubjects: string[]; graduateRule: string | null; overseasDegreeRule: string | null
  experienceEvidenceRule: string | null; employmentStatement: string | null; interviewRule: string | null
}

export type PlanningOrganization = {
  id: string; name: string; organizationType: string; province: string | null; city: string | null
  district: string | null; officialWebsite: string | null
}

export type PlanningJobDetail = {
  job: PlanningJob; event: PlanningEvent; organization: PlanningOrganization
  scenarioOutcomes: JobScenarioOutcome[]
}

export async function getPlanningJobDetail(jobId: string, candidateId: string, targetYear: number): Promise<PlanningJobDetail> {
  const [job, plan] = await Promise.all([
    requestJson<PlanningJob>(`/api/v1/jobs/${jobId}`),
    getCareerPlan(candidateId, targetYear),
  ])
  const [event, organization] = await Promise.all([
    requestJson<PlanningEvent>(`/api/v1/recruitment-events/${job.recruitmentEventId}`),
    requestJson<PlanningOrganization>(`/api/v1/organizations/${job.organizationId}`),
  ])
  const representative = plan.recommendedRoutes.flatMap(route => route.representativeJobs)
    .find(value => value.jobId === jobId)
  return { job, event, organization, scenarioOutcomes: representative?.scenarioOutcomes ?? [] }
}

export const planningKeys = { plan: queryKeys.careerPlan }
