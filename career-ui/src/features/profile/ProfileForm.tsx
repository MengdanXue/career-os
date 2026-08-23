import { useEffect, useState, type FormEvent } from 'react'
import { ApiProblem } from '../../api/http'
import type { CandidateEmploymentRecord, CandidateProfile, CandidateProfileUpdate, EducationRecord, Gender, PoliticalAffiliation } from './profileSchema'
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
  birthDate: string
  gender: Gender
  politicalAffiliation: PoliticalAffiliation
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
  educationRecords: EducationRecord[]
  employmentRecords: CandidateEmploymentRecord[]
}

function isoBirthDate(candidate: CandidateProfile) {
  const { year, month, day } = candidate.birthDate
  return day ? `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}` : ''
}

function initialFields(candidate: CandidateProfile): Fields {
  return {
    displayName: candidate.displayName,
    birthDate: isoBirthDate(candidate),
    gender: candidate.gender ?? 'UNKNOWN',
    politicalAffiliation: candidate.politicalAffiliation ?? 'UNKNOWN',
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
    educationRecords: (candidate.educationRecords ?? []).map(record => ({ ...record })),
    employmentRecords: (candidate.employmentRecords ?? []).map(record => ({ ...record, evidenceTypes: [...record.evidenceTypes] })),
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
    const [birthYear, birthMonth, birthDay] = fields.birthDate.split('-').map(Number)
    onSubmit({
      displayName: fields.displayName.trim(),
      birthYear,
      birthMonth,
      birthDay,
      gender: fields.gender,
      politicalAffiliation: fields.politicalAffiliation,
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
      educationRecords: fields.educationRecords.map(record => ({
        ...record,
        institutionName: record.institutionName?.trim() || null,
        countryOrRegion: record.countryOrRegion?.trim() || null,
        majorName: record.majorName.trim(),
        graduationYear: record.graduationYear ? Number(record.graduationYear) : null,
        graduationMonth: record.graduationMonth ? Number(record.graduationMonth) : null,
      })),
      employmentRecords: fields.employmentRecords.map(record => ({
        ...record,
        employerName: record.employerName.trim(),
        roleTitle: record.roleTitle.trim(),
        endsOn: record.endsOn || null,
        evidenceTypes: record.evidenceTypes.map(value => value.trim()).filter(Boolean),
      })),
    })
  }

  function setEducation(index: number, patch: Partial<EducationRecord>) {
    set('educationRecords', fields.educationRecords.map((record, recordIndex) => recordIndex === index ? { ...record, ...patch } : record))
  }

  function setEmployment(index: number, patch: Partial<CandidateEmploymentRecord>) {
    set('employmentRecords', fields.employmentRecords.map((record, recordIndex) => recordIndex === index ? { ...record, ...patch } : record))
  }

  function addEmployment() {
    set('employmentRecords', [...fields.employmentRecords, {
      employerName: '', roleTitle: '', startsOn: '', endsOn: null, employmentMode: 'FULL_TIME',
      verificationStatus: 'UNVERIFIED', evidenceTypes: [],
    }])
  }

  const errorMessage = error instanceof ApiProblem ? error.message : error ? '资料没有保存，请重试。' : null
  return (
    <form className="profile-form" onSubmit={submit}>
      <fieldset disabled={pending}>
        <legend>基本资格</legend>
        <div className="form-grid">
          <label>称呼<input required value={fields.displayName} onChange={event => set('displayName', event.target.value)} /></label>
          <label>最高学历<select value={fields.highestEducation} onChange={event => set('highestEducation', event.target.value)}><option value="BACHELOR">本科</option><option value="MASTER">硕士</option><option value="DOCTOR">博士</option></select></label>
          <label>出生日期<input aria-label="出生日期" required type="date" value={fields.birthDate} onChange={event => set('birthDate', event.target.value)} /></label>
          <label>性别<select aria-label="性别" value={fields.gender} onChange={event => set('gender', event.target.value as Gender)}><option value="FEMALE">女</option><option value="MALE">男</option><option value="OTHER">其他</option><option value="UNKNOWN">待明确</option></select></label>
          <label>政治面貌<select aria-label="政治面貌" value={fields.politicalAffiliation} onChange={event => set('politicalAffiliation', event.target.value as PoliticalAffiliation)}><option value="UNKNOWN">待明确</option><option value="CPC_MEMBER">中共党员</option><option value="CPC_PROBATIONARY">中共预备党员</option><option value="NON_MEMBER">非中共党员</option></select></label>
          <label>毕业年份<input inputMode="numeric" value={fields.graduationYear} onChange={event => set('graduationYear', event.target.value)} /></label>
          <label>相关经验（年）<input type="number" min="0" value={fields.experienceYears} onChange={event => set('experienceYears', event.target.value)} /></label>
        </div>
      </fieldset>
      <fieldset disabled={pending}>
        <legend>经历证据</legend>
        <h2 className="form-section-title">可核验工作经历</h2>
        <p className="field-note">只填写能说明起止日期和证明类型的经历。空白比编造时间更可靠。</p>
        <div className="employment-records">
          {fields.employmentRecords.map((record, index) => <section className="employment-record" key={`employment-${index}`} aria-label={`工作经历 ${index + 1}`}>
            <div className="education-record-heading"><strong>第 {index + 1} 段经历</strong><button className="text-action" type="button" onClick={() => set('employmentRecords', fields.employmentRecords.filter((_, recordIndex) => recordIndex !== index))}>移除</button></div>
            <div className="form-grid">
              <label>单位<input required value={record.employerName} onChange={event => setEmployment(index, { employerName: event.target.value })} /></label>
              <label>岗位<input required value={record.roleTitle} onChange={event => setEmployment(index, { roleTitle: event.target.value })} /></label>
              <label>开始日期<input required type="date" value={record.startsOn} onChange={event => setEmployment(index, { startsOn: event.target.value })} /></label>
              <label>结束日期<input type="date" value={record.endsOn ?? ''} onChange={event => setEmployment(index, { endsOn: event.target.value || null })} /></label>
              <label>经历类型<select value={record.employmentMode} onChange={event => setEmployment(index, { employmentMode: event.target.value as CandidateEmploymentRecord['employmentMode'] })}><option value="FULL_TIME">全职</option><option value="PART_TIME">兼职</option><option value="INTERNSHIP">实习</option><option value="UNKNOWN">待明确</option></select></label>
              <label>证明状态<select value={record.verificationStatus} onChange={event => setEmployment(index, { verificationStatus: event.target.value as CandidateEmploymentRecord['verificationStatus'] })}><option value="UNVERIFIED">未核验</option><option value="PARTIAL">部分证明</option><option value="VERIFIED">已核验</option><option value="REJECTED">不可用于证明</option></select></label>
            </div>
            <label>可提供的证明类型<input value={record.evidenceTypes.join(', ')} onChange={event => setEmployment(index, { evidenceTypes: splitFacts(event.target.value) })} placeholder="例如：劳动合同、社保记录、单位证明" /></label>
          </section>)}
          {fields.employmentRecords.length === 0 && <p className="empty-evidence">尚未记录可核验经历；系统不会从简历自动猜测工作年限。</p>}
        </div>
        <button className="secondary-action" type="button" onClick={addEmployment}>添加一段经历</button>
      </fieldset>
      <fieldset disabled={pending}>
        <legend>教育经历</legend>
        <p className="field-note">已取得学历和预计毕业学历分开记录。预计毕业不会提前作为已取得学历参与资格判断。</p>
        <div className="education-records">
          {fields.educationRecords.map((record, index) => <section className="education-record" key={`${record.educationLevel}-${index}`} aria-label={`教育经历 ${index + 1}`}>
            <div className="education-record-heading">
              <strong>{record.completionStatus === 'COMPLETED' ? '已毕业' : '预计毕业'}</strong>
              <span>第 {index + 1} 段</span>
            </div>
            <div className="form-grid">
              <label>学校<input aria-label={`学校 ${index + 1}`} value={record.institutionName ?? ''} onChange={event => setEducation(index, { institutionName: event.target.value })} placeholder="学校待补充也可以留空" /></label>
              <label>国家或地区<input value={record.countryOrRegion ?? ''} onChange={event => setEducation(index, { countryOrRegion: event.target.value })} /></label>
              <label>学历<select value={record.educationLevel} onChange={event => setEducation(index, { educationLevel: event.target.value as EducationRecord['educationLevel'] })}><option value="ASSOCIATE">专科</option><option value="BACHELOR">本科</option><option value="MASTER">硕士</option><option value="DOCTORATE">博士</option></select></label>
              <label>专业<input aria-label={`教育专业 ${index + 1}`} required value={record.majorName} onChange={event => setEducation(index, { majorName: event.target.value })} /></label>
              <label>毕业年份<input type="number" min="1900" max="2100" value={record.graduationYear ?? ''} onChange={event => setEducation(index, { graduationYear: event.target.value ? Number(event.target.value) : null })} /></label>
              <label>完成状态<select value={record.completionStatus} onChange={event => setEducation(index, { completionStatus: event.target.value as EducationRecord['completionStatus'] })}><option value="COMPLETED">已毕业</option><option value="EXPECTED">预计毕业</option></select></label>
              <label>学历认证<select value={record.credentialVerificationStatus} onChange={event => setEducation(index, { credentialVerificationStatus: event.target.value as EducationRecord['credentialVerificationStatus'] })}><option value="UNKNOWN">待明确</option><option value="PLANNED">计划办理</option><option value="IN_PROGRESS">办理中</option><option value="VERIFIED">已认证</option><option value="NOT_REQUIRED">无需认证</option></select></label>
            </div>
          </section>)}
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
