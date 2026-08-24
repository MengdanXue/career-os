import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { queryKeys } from '../../api/http'
import { AsyncState } from '../../components/AsyncState'
import { getPlanningJobDetail } from './planningApi'

const employmentLabels: Record<string, string> = {
  ESTABLISHMENT: '事业编制', PUBLIC_INSTITUTION_FORMAL: '事业单位正式聘用',
  PERSONNEL_AGENCY: '人事代理', CONTRACT: '合同制', LABOR_DISPATCH: '劳务派遣',
  PROJECT_BASED: '项目制', UNKNOWN: '官网未明确',
}
const educationLabels: Record<string, string> = {
  DOCTORATE: '博士研究生', MASTER: '硕士研究生', BACHELOR: '本科', ASSOCIATE: '大专',
  HIGH_SCHOOL: '高中/中专', UNKNOWN: '官网未明确',
}
const scenarioLabels: Record<string, string> = { MASTER_IN_PROGRESS: '境外硕士在读', PRE_GRADUATION: '本科阶段（兼容值）', DEGREE_PENDING_VERIFICATION: '硕士待认证', MASTER_VERIFIED: '硕士已认证' }
const outcomeLabels: Record<string, string> = { ELIGIBLE: '可报', CONDITIONALLY_ELIGIBLE: '条件可报', UNCERTAIN: '待确认', INELIGIBLE: '不可报' }
const evidenceStateLabels: Record<string, string> = {
  CONFIRMED: '官网已明确', NOT_REQUIRED: '官网明确不要求', NOT_PUBLISHED: '官网说明另行通知',
  NOT_COLLECTED: '系统尚未采集该通知', PARSE_FAILED: '官网原件自动解析失败',
  REVIEW_REQUIRED: '官网规则有歧义，等待复核', UNKNOWN: '当前证据无法判断',
}

function values(items: Array<string | number> | undefined, fallback = '官网未明确') {
  return items && items.length > 0 ? items.join('、') : fallback
}

function dateOnly(value: string | null | undefined) { return value ? value.slice(0, 10) : null }

function processValue(label: string, value: string | null | undefined, state: string) {
  return `${label}：${dateOnly(value) ?? evidenceStateLabels[state] ?? evidenceStateLabels.UNKNOWN}`
}

function inferredState(value: string | null | undefined) { return value ? 'CONFIRMED' : 'UNKNOWN' }

function dateRange(starts: string | null, ends: string | null) {
  if (starts && ends) return `${starts} 至 ${ends}`
  if (starts) return `自 ${starts} 起`
  if (ends) return `截至 ${ends}`
  return '官网未明确'
}

function looksLikeAttachment(url: string) {
  return /(?:\.xlsx?|\.pdf|\.docx?)(?:$|[?#])|(?:fileName|filename)=[^&]*(?:xlsx?|pdf|docx?)/i.test(url)
}

export function PlanningJobDetailPage() {
  const { jobId } = useParams()
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const targetYear = new Date().getFullYear() + 1
  const detail = useQuery({
    queryKey: [...queryKeys.planningJob(jobId ?? ''), candidateId, targetYear],
    queryFn: () => getPlanningJobDetail(jobId!, candidateId, targetYear),
    enabled: Boolean(jobId),
  })

  if (!jobId) return <main className="page-frame"><p>缺少岗位编号。</p></main>
  const data = detail.data
  const applicationStarts = dateOnly(data?.event.applicationStartsAt) ?? data?.event.applicationStartsOn ?? null
  const applicationEnds = dateOnly(data?.event.applicationEndsAt) ?? data?.event.applicationEndsOn ?? null
  const eventIsAttachment = data ? looksLikeAttachment(data.event.sourceUrl) : false
  const jobIsAttachment = data ? looksLikeAttachment(data.job.sourceUrl) : false
  const noticeUrl = data ? (eventIsAttachment && !jobIsAttachment ? data.job.sourceUrl : data.event.sourceUrl) : ''
  const attachmentUrl = data ? (jobIsAttachment ? data.job.sourceUrl : eventIsAttachment ? data.event.sourceUrl : data.job.sourceUrl) : ''
  const process = data?.event.processFacts
  const noticeState = process?.notice.state ?? inferredState(data?.event.publishedOn)
  const applicationState = process?.application.state ?? inferredState(applicationStarts)
  const qualificationState = process?.qualificationReview.state ?? inferredState(data?.event.qualificationReviewEndsOn)
  const paymentState = process?.payment.state ?? inferredState(data?.event.paymentEndsOn)
  const ticketState = process?.admissionTicket.state ?? inferredState(data?.event.admissionTicketStartsOn)

  return <main className="planning-job-page page-frame wide-frame">
    <Link className="planning-back-link" to="/plan">← 返回我的规划</Link>
    <AsyncState loading={detail.isLoading} error={detail.error}>
      {data && <>
        <header className="planning-job-hero">
          <div><p className="eyebrow">OFFICIAL ARCHIVE · 官网历史岗位事实</p><h1>{data.job.title}</h1><p>{data.organization.name} · {data.event.title}</p></div>
          <div className="archive-year"><strong>{data.event.recruitmentYear}</strong><span>招聘年度</span></div>
        </header>

        <aside className="historical-disclaimer" role="note"><strong>历史岗位事实，不代表当前仍可报名</strong><span>用于判断未来可能机会与准备节奏；是否可报必须以新公告和当时画像重新计算。</span></aside>

        {(data.historicalActual || data.targetYearAnalog) && <section className="official-detail-block projected-outcome-block"><header><p className="section-number">目标年份投影</p><h2>{targetYear} 同类岗位推演</h2></header><div>
          {data.historicalActual && <article data-outcome={data.historicalActual.outcome.toLowerCase()}><strong>历史岗位当年：{outcomeLabels[data.historicalActual.outcome]}</strong><ul>{data.historicalActual.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul></article>}
          {data.targetYearAnalog && <article data-outcome={data.targetYearAnalog.outcome.toLowerCase()}><strong>{targetYear} 同类岗位：{outcomeLabels[data.targetYearAnalog.outcome]}</strong><ul>{data.targetYearAnalog.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul></article>}
        </div><p className="conditional-note">同类岗位推演只平移相对届别规则；新公告的年龄、专业、认证和社保条件仍需重新计算。</p></section>}

        <section className="official-detail-block scenario-outcome-block"><header><p className="section-number">资格判断</p><h2>按你的三个阶段分别判断</h2></header>
          {data.scenarioOutcomes.length > 0 ? <div className="job-scenario-outcomes">{data.scenarioOutcomes.map(value => <article key={value.scenarioCode} data-outcome={value.outcome.toLowerCase()}><span>{scenarioLabels[value.scenarioCode] ?? value.scenarioCode}</span><h3>{outcomeLabels[value.outcome] ?? value.outcome}</h3><ul>{value.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul></article>)}</div> : <p className="conditional-note">该岗位不在当前规划分析范围内，暂不生成个人资格结论；下方仍展示全部官网事实。</p>}
        </section>

        <section className="official-detail-block"><header><p className="section-number">01 · 岗位身份</p><h2>单位与用工</h2></header>
          <dl className="planning-fact-grid">
            <div><dt>单位</dt><dd>{data.organization.name}</dd></div><div><dt>主管部门</dt><dd>{data.job.supervisingDepartment ?? '官网未明确'}</dd></div>
            <div><dt>用工性质</dt><dd>{employmentLabels[data.job.employmentType] ?? data.job.employmentType}</dd></div><div><dt>岗位代码</dt><dd>{data.job.externalJobCode ?? '官网未明确'}</dd></div>
            <div><dt>岗位类别</dt><dd>{data.job.jobCategory ?? '官网未明确'}</dd></div><div><dt>岗位等级</dt><dd>{data.job.jobGrade ?? '官网未明确'}</dd></div>
            <div><dt>招聘人数</dt><dd>{data.job.headcount} 人</dd></div><div><dt>工作地点</dt><dd>{data.job.location ?? ([data.organization.city, data.organization.district].filter(Boolean).join(' · ') || '官网未明确')}</dd></div>
          </dl>
        </section>

        <section className="official-detail-block"><header><p className="section-number">02 · 报考条件</p><h2>能不能报，看这些原始条件</h2></header>
          <dl className="planning-fact-grid">
            <div><dt>学历</dt><dd>{data.job.educationRequirementText ?? educationLabels[data.job.minimumEducation] ?? data.job.minimumEducation}</dd></div>
            <div><dt>学位</dt><dd>{data.job.degreeRequirement ?? '官网未明确'}</dd></div>
            <div className="wide"><dt>专业</dt><dd>{data.job.majorRequirementText ?? values(data.job.exactMajors)}</dd></div>
            <div><dt>年龄</dt><dd>{data.job.ageRequirementText ?? (data.job.maximumAge ? `${data.job.maximumAge} 周岁及以下` : '官网未明确')}{data.job.ageReferenceDate ? `（计算至 ${data.job.ageReferenceDate}）` : ''}</dd></div>
            <div><dt>性别</dt><dd>{data.job.genderRequirement ?? '官网未明确'}</dd></div>
            <div><dt>人员范围</dt><dd>{data.job.candidateScope ?? '官网未明确'}</dd></div>
            <div><dt>工作经历</dt><dd>{data.job.minimumExperienceYears === null ? '官网未明确' : `${data.job.minimumExperienceYears} 年及以上`}</dd></div>
            <div><dt>职称</dt><dd>{values(data.job.requiredProfessionalTitles)}</dd></div>
            {data.job.otherRequirements && <div className="wide"><dt>其他条件</dt><dd>{data.job.otherRequirements}</dd></div>}
          </dl>
          <div className="planning-rule-notes">
            {data.event.graduateRule && <p><strong>应届生范围</strong>{data.event.graduateRule}</p>}
            {data.event.overseasDegreeRule && <p><strong>境外学历</strong>{data.event.overseasDegreeRule}</p>}
            {data.event.experienceEvidenceRule && <p><strong>经历证明</strong>{data.event.experienceEvidenceRule}</p>}
            {data.event.employmentStatement && <p><strong>录用说明</strong>{data.event.employmentStatement}</p>}
          </div>
        </section>

        <section className="official-detail-block"><header><p className="section-number">03 · 招聘流程</p><h2>从公告到聘用，站内查看完整流程</h2></header>
          <ol className="planning-process">
            <li data-state={noticeState}><span>公告</span><strong>公告发布：{data.event.publishedOn ?? evidenceStateLabels[noticeState]}</strong><small>证据状态：{evidenceStateLabels[noticeState]}</small></li>
            <li data-state={applicationState}><span>报名</span><strong>报名：{applicationStarts || applicationEnds ? dateRange(applicationStarts, applicationEnds) : evidenceStateLabels[applicationState]}</strong><small>证据状态：{evidenceStateLabels[applicationState]}</small>{data.event.registrationUrl && <a href={data.event.registrationUrl} target="_blank" rel="noreferrer">报名入口（历史）</a>}</li>
            <li data-state={qualificationState}><span>资格初审</span><strong>{processValue('资格初审截止', data.event.qualificationReviewEndsOn, qualificationState)}</strong><small>证据状态：{evidenceStateLabels[qualificationState]}</small></li>
            <li data-state={paymentState}><span>缴费</span><strong>{processValue('缴费截止', data.event.paymentEndsOn, paymentState)}</strong><small>证据状态：{evidenceStateLabels[paymentState]}</small></li>
            <li data-state={ticketState}><span>准考证</span><strong>准考证：{data.event.admissionTicketStartsOn || data.event.admissionTicketEndsOn ? dateRange(data.event.admissionTicketStartsOn, data.event.admissionTicketEndsOn) : evidenceStateLabels[ticketState]}</strong><small>证据状态：{evidenceStateLabels[ticketState]}</small></li>
            <li data-state={data.event.writtenExamState}><span>笔试</span><strong>{processValue('笔试', data.event.writtenExamOn, data.event.writtenExamState)}</strong><small>证据状态：{evidenceStateLabels[data.event.writtenExamState]}</small><small>{values(data.event.writtenExamSubjects)}</small></li>
            <li data-state={data.event.professionalTestState}><span>专业测试</span><strong>专业测试：{evidenceStateLabels[data.event.professionalTestState]}</strong><small>证据状态：{evidenceStateLabels[data.event.professionalTestState]}</small>{data.job.professionalTestRequired && <small>岗位表标注需要专业测试</small>}</li>
            <li data-state={data.event.interviewState}><span>面试</span><strong>{processValue('面试时间', data.event.interviewOn, data.event.interviewState)}</strong><small>证据状态：{evidenceStateLabels[data.event.interviewState]}</small>{data.event.interviewMethod && <small>方式：{data.event.interviewMethod}</small>}{data.job.interviewRatio && <small>入围比例 {data.job.interviewRatio}</small>}{data.event.interviewRule && <small>{data.event.interviewRule}</small>}</li>
            <li data-state={process?.physicalExam.state ?? 'NOT_COLLECTED'}><span>体检</span><strong>{process?.physicalExam.detail ?? evidenceStateLabels[process?.physicalExam.state ?? 'NOT_COLLECTED']}</strong><small>证据状态：{evidenceStateLabels[process?.physicalExam.state ?? 'NOT_COLLECTED']}</small></li>
            <li data-state={process?.investigation.state ?? 'NOT_COLLECTED'}><span>考察</span><strong>{process?.investigation.detail ?? evidenceStateLabels[process?.investigation.state ?? 'NOT_COLLECTED']}</strong><small>证据状态：{evidenceStateLabels[process?.investigation.state ?? 'NOT_COLLECTED']}</small></li>
            <li data-state={process?.publication.state ?? 'NOT_COLLECTED'}><span>公示</span><strong>{process?.publication.detail ?? evidenceStateLabels[process?.publication.state ?? 'NOT_COLLECTED']}</strong><small>证据状态：{evidenceStateLabels[process?.publication.state ?? 'NOT_COLLECTED']}</small></li>
            <li data-state={process?.appointment.state ?? (data.event.employmentStatement ? 'CONFIRMED' : 'NOT_COLLECTED')}><span>聘用</span><strong>{process?.appointment.detail ?? data.event.employmentStatement ?? evidenceStateLabels.NOT_COLLECTED}</strong><small>证据状态：{evidenceStateLabels[process?.appointment.state ?? (data.event.employmentStatement ? 'CONFIRMED' : 'NOT_COLLECTED')]}</small>{data.event.scoreFormula && <small>成绩：{data.event.scoreFormula}</small>}</li>
          </ol>
        </section>

        <section className="official-detail-block"><header><p className="section-number">04 · 岗位内容</p><h2>职责与原始岗位表</h2></header>
          <p className="planning-job-duties">{data.job.duties ?? '官网岗位表未单列日常职责；系统不自行补写。'}</p>
          {data.job.originalRequirementText && <details className="planning-original"><summary>查看岗位表原始条件</summary><p>{data.job.originalRequirementText}</p></details>}
        </section>

        <footer className="planning-official-links"><div><strong>官方证据入口</strong><p>站内信息用于快速判断，原公告与附件永久保留用于复核。</p></div><nav><a href={noticeUrl} target="_blank" rel="noreferrer">查看官方公告</a><a href={attachmentUrl} target="_blank" rel="noreferrer">查看岗位附件</a></nav></footer>
      </>}
    </AsyncState>
  </main>
}
