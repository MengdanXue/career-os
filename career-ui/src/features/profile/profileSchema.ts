export type CandidateProfile = {
  id: string
  displayName: string
  birthDate: { year: number; month: number; day: number | null }
  highestEducation: string
  majors: string[]
  graduationYear: number | null
  experienceYears: number | null
  professionalTitles: string[]
  preferredLocations: string[]
  acceptedEmploymentTypes: string[]
  profileVersion: string
  skills: string[]
  researchKeywords: string[]
  targetJobFamilies: string[]
  preferredOrganizationTypes: string[]
}

export const candidateFactKeys = [
  'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR', 'EXPERIENCE_YEARS',
  'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS', 'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS',
  'RESEARCH_KEYWORDS', 'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES',
] as const

export type CandidateFactKey = typeof candidateFactKeys[number]
export type CandidateFactStatus = 'UNCONFIRMED' | 'CONFIRMED' | 'UNKNOWN'

export type CandidateProfileFacts = {
  profile: CandidateProfile
  statuses: Record<CandidateFactKey, CandidateFactStatus>
  confirmedCount: number
  unconfirmedCount: number
  unknownCount: number
  decisionReady: boolean
}

export type CandidateProfileUpdate = {
  displayName: string
  birthYear: number
  birthMonth: number
  birthDay: number | null
  highestEducation: string
  majors: string[]
  graduationYear: number | null
  experienceYears: number | null
  professionalTitles: string[]
  preferredLocations: string[]
  acceptedEmploymentTypes: string[]
  skills: string[]
  researchKeywords: string[]
  targetJobFamilies: string[]
  preferredOrganizationTypes: string[]
}

export function splitFacts(value: string) {
  return [...new Set(value.split(/[,，、\n]/).map(item => item.trim()).filter(Boolean))]
}
