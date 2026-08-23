export type EducationRecord = {
  institutionName: string | null
  countryOrRegion: string | null
  educationLevel: 'HIGH_SCHOOL' | 'ASSOCIATE' | 'BACHELOR' | 'MASTER' | 'DOCTORATE'
  majorName: string
  graduationYear: number | null
  graduationMonth: number | null
  completionStatus: 'COMPLETED' | 'EXPECTED'
  credentialVerificationStatus: 'NOT_REQUIRED' | 'PLANNED' | 'IN_PROGRESS' | 'VERIFIED' | 'UNKNOWN'
}

export type Gender = 'FEMALE' | 'MALE' | 'OTHER' | 'UNKNOWN'
export type PoliticalAffiliation = 'CPC_MEMBER' | 'CPC_PROBATIONARY' | 'NON_MEMBER' | 'UNKNOWN'
export type CandidateEmploymentRecord = {
  employerName: string
  roleTitle: string
  startsOn: string
  endsOn: string | null
  employmentMode: 'FULL_TIME' | 'PART_TIME' | 'INTERNSHIP' | 'UNKNOWN'
  verificationStatus: 'UNVERIFIED' | 'PARTIAL' | 'VERIFIED' | 'REJECTED'
  evidenceTypes: string[]
}

export type CandidateProfile = {
  id: string
  displayName: string
  birthDate: { year: number; month: number; day: number | null }
  gender: Gender
  politicalAffiliation: PoliticalAffiliation
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
  educationRecords: EducationRecord[]
  employmentRecords: CandidateEmploymentRecord[]
}

export const candidateFactKeys = [
  'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR', 'EXPERIENCE_YEARS',
  'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS', 'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS',
  'RESEARCH_KEYWORDS', 'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES',
  'EDUCATION_RECORDS', 'GENDER', 'POLITICAL_AFFILIATION', 'EMPLOYMENT_HISTORY',
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
  gender: Gender
  politicalAffiliation: PoliticalAffiliation
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
  educationRecords: EducationRecord[]
  employmentRecords: CandidateEmploymentRecord[]
}

export function splitFacts(value: string) {
  return [...new Set(value.split(/[,，、\n]/).map(item => item.trim()).filter(Boolean))]
}
