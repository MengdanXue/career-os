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
  profileVersion: string
  skills: string[]
  researchKeywords: string[]
  targetJobFamilies: string[]
  preferredOrganizationTypes: string[]
}

export function splitFacts(value: string) {
  return [...new Set(value.split(/[,，、\n]/).map(item => item.trim()).filter(Boolean))]
}

export function newProfileVersion() {
  return `profile-ui-${crypto.randomUUID()}`
}

export function confirmationKey(candidateId: string) {
  return `career-os.profile-confirmed.${candidateId}`
}
