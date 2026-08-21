import { useEffect, useState, type FormEvent } from 'react'
import { ApiProblem } from '../../api/http'
import type { CandidateProfile, CandidateProfileUpdate } from './profileSchema'
import { splitFacts } from './profileSchema'

type Props = {
  candidate: CandidateProfile
  submitLabel: string
  error: unknown
  pending: boolean
  onSubmit: (value: CandidateProfileUpdate) => void
}

type Fields = {
  displayName: string
  birthYear: string
  birthMonth: string
  highestEducation: string
  majors: string
  graduationYear: string
  experienceYears: string
  professionalTitles: string
  preferredLocations: string
  acceptedEmploymentTypes: string[]
  skills: string
  researchKeywords: string
  targetJobFamilies: string[]
  preferredOrganizationTypes: string[]
}

function initialFields(candidate: CandidateProfile): Fields {
  return {
    displayName: candidate.displayName,
    birthYear: String(candidate.birthDate.year),
    birthMonth: String(candidate.birthDate.month),
    highestEducation: candidate.highestEducation,
    majors: candidate.majors.join(', '),
    graduationYear: candidate.graduationYear?.toString() ?? '',
    experienceYears: candidate.experienceYears?.toString() ?? '',
    professionalTitles: candidate.professionalTitles.join(', '),
    preferredLocations: candidate.preferredLocations.join(', '),
    acceptedEmploymentTypes: candidate.acceptedEmploymentTypes,
    skills: candidate.skills.join(', '),
    researchKeywords: candidate.researchKeywords.join(', '),
    targetJobFamilies: candidate.targetJobFamilies,
    preferredOrganizationTypes: candidate.preferredOrganizationTypes,
  }
}

export function ProfileForm({ candidate, submitLabel, error, pending, onSubmit }: Props) {
  const [fields, setFields] = useState(() => initialFields(candidate))
  useEffect(() => setFields(initialFields(candidate)), [candidate])

  function set<K extends keyof Fields>(key: K, value: Fields[K]) {
    setFields(previous => ({ ...previous, [key]: value }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    onSubmit({
      displayName: fields.displayName.trim(),
      birthYear: Number(fields.birthYear),
      birthMonth: Number(fields.birthMonth),
      birthDay: candidate.birthDate.day,
      highestEducation: fields.highestEducation,
      majors: splitFacts(fields.majors),
      graduationYear: fields.graduationYear ? Number(fields.graduationYear) : null,
      experienceYears: fields.experienceYears ? Number(fields.experienceYears) : null,
      professionalTitles: splitFacts(fields.professionalTitles),
      preferredLocations: splitFacts(fields.preferredLocations),
      acceptedEmploymentTypes: fields.acceptedEmploymentTypes,
      skills: splitFacts(fields.skills),
      researchKeywords: splitFacts(fields.researchKeywords),
      targetJobFamilies: fields.targetJobFamilies,
      preferredOrganizationTypes: fields.preferredOrganizationTypes,
    })
  }

  const errorMessage = error instanceof ApiProblem ? error.message : error ? '资料没有保存，请重试。' : null
  return (
    <form className="profile-form" onSubmit={submit}>
      <fieldset disabled={pending}>
        <legend>基本资格</legend>
        <div className="form-grid">
          <label>称呼<input required value={fields.displayName} onChange={event => set('displayName', event.target.value)} /></label>
          <label>最高学历<select value={fields.highestEducation} onChange={event => set('highestEducation', event.target.value)}><option value="BACHELOR">本科</option><option value="MASTER">硕士</option><option value="DOCTOR">博士</option></select></label>
          <label>出生年份<input required inputMode="numeric" value={fields.birthYear} onChange={event => set('birthYear', event.target.value)} /></label>
          <label>出生月份<input required type="number" min="1" max="12" value={fields.birthMonth} onChange={event => set('birthMonth', event.target.value)} /></label>
          <label>毕业年份<input inputMode="numeric" value={fields.graduationYear} onChange={event => set('graduationYear', event.target.value)} /></label>
          <label>相关经验（年）<input type="number" min="0" value={fields.experienceYears} onChange={event => set('experienceYears', event.target.value)} /></label>
        </div>
      </fieldset>
      <fieldset disabled={pending}>
        <legend>岗位匹配依据</legend>
        <label>专业<input aria-label="专业" required value={fields.majors} onChange={event => set('majors', event.target.value)} /><small>多个专业用逗号分隔；用于检查公告中的专业硬条件。</small></label>
        <label>专业职称<input aria-label="专业职称" value={fields.professionalTitles} onChange={event => set('professionalTitles', event.target.value)} /><small>没有职称可以留空；确认后会作为明确没有参与判断。</small></label>
        <label>技能关键词<input aria-label="技能关键词" value={fields.skills} onChange={event => set('skills', event.target.value)} /><small>只填写你能用经历或作品证明的技能。</small></label>
        <label>研究或业务关键词<input value={fields.researchKeywords} onChange={event => set('researchKeywords', event.target.value)} /></label>
      </fieldset>
      <fieldset disabled={pending}>
        <legend>稳定岗位偏好</legend>
        <label>目标地点<input aria-label="目标地点" required value={fields.preferredLocations} onChange={event => set('preferredLocations', event.target.value)} /></label>
        <div className="check-row" aria-label="接受的用工形式">
          {([
            ['ESTABLISHMENT', '事业编制'],
            ['PUBLIC_INSTITUTION_FORMAL', '事业单位正式聘用'],
            ['CONTRACT', '单位合同'],
            ['LABOR_DISPATCH', '劳务派遣'],
          ] as const).map(([type, label]) => <label key={type}><input type="checkbox" checked={fields.acceptedEmploymentTypes.includes(type)} onChange={event => set('acceptedEmploymentTypes', event.target.checked ? [...fields.acceptedEmploymentTypes, type] : fields.acceptedEmploymentTypes.filter(value => value !== type))} />{label}</label>)}
        </div>
      </fieldset>
      {errorMessage && <div className="form-error" role="alert">{errorMessage}</div>}
      <button className="primary-action" type="submit">{pending ? '正在保存…' : error ? '重新提交' : submitLabel}</button>
    </form>
  )
}
