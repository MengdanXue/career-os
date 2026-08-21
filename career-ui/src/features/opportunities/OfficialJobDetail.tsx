import type { Ref } from 'react'
import type { CandidateMatch, EducationLevel, EmploymentType, JobFamily } from './candidateMatchApi'

const employmentLabels: Record<EmploymentType, string> = {
  ESTABLISHMENT: '事业编制',
  PUBLIC_INSTITUTION_FORMAL: '事业单位正式聘用',
  CONTRACT: '合同制',
  PERSONNEL_AGENCY: '人事代理',
  LABOR_DISPATCH: '劳务派遣',
  PROJECT_BASED: '项目制',
  UNKNOWN: '公告尚未说明用工性质',
}

const educationLabels: Record<EducationLevel, string> = {
  DOCTORATE: '博士研究生', MASTER: '硕士研究生', BACHELOR: '本科',
  ASSOCIATE: '大专', HIGH_SCHOOL: '高中/中专', UNKNOWN: '官网未明确',
}

const familyLabels: Record<JobFamily, string> = {
  INFORMATION_SYSTEMS: '信息系统', DATA: '数据', SOFTWARE: '软件研发', AI: '人工智能',
  CYBERSECURITY: '网络与安全', DIGITALIZATION: '数字化', IT_OPERATIONS: 'IT 运维',
  RESEARCH: '科研', PRODUCT: '产品', OTHER: '其他技术岗位',
}

function factList(values: Array<string | number> | null | undefined, empty: string) {
  return values && values.length > 0 ? values.join('、') : empty
}

function officialTime(value: string | null) {
  if (!value) return null
  const parts = Object.fromEntries(new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(value)).map(part => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

function applicationPeriod(match: CandidateMatch) {
  const starts = officialTime(match.applicationStartsAt)
  const ends = officialTime(match.applicationEndsAt)
  if (starts && ends) return `${starts} 至 ${ends}`
  if (starts) return `自 ${starts} 起`
  if (ends) return `截至 ${ends}`
  if (match.applicationStartsOn && match.applicationEndsOn) {
    return `${match.applicationStartsOn} 至 ${match.applicationEndsOn}`
  }
  if (match.applicationStartsOn) return `自 ${match.applicationStartsOn} 起`
  if (match.applicationEndsOn) return `截至 ${match.applicationEndsOn}`
  return '公告未明确'
}

function dateRange(starts: string | null, ends: string | null) {
  if (starts && ends) return starts === ends ? starts : `${starts} 至 ${ends}`
  return starts ?? ends ?? '公告未单列'
}

export function OfficialJobDetail({ match, onClose, detailRef }: {
  match: CandidateMatch
  onClose: () => void
  detailRef?: Ref<HTMLElement>
}) {
  const identity = employmentLabels[match.employmentType] ?? match.employmentType
  const verified = match.dataQualityStatus === 'VERIFIED'
  const majorText = match.majorRequirementText
    ?? (match.exactMajors.length ? `系统归一化：${factList(match.exactMajors, '')}` : '官网未明确')
  const educationText = match.educationRequirementText
    ?? (match.minimumEducation === 'UNKNOWN' ? '官网未明确' : `系统归一化：${educationLabels[match.minimumEducation]}`)
  const ageText = match.ageRequirementText
    ?? (match.maximumAge ? `系统归一化：${match.maximumAge} 周岁及以下` : '官网未明确')
  const headcountMissing = match.qualitySummary?.missingFields.includes('headcount')
  const evidenceReferences = match.qualitySummary?.evidenceReferences ?? []

  return <aside id={`official-job-detail-${match.jobId}`} ref={detailRef} tabIndex={-1}
    className="official-job-detail" aria-label={`${match.jobTitle} 官网岗位详情`}>
    <header>
      <div>
        <p className="section-number">OFFICIAL FACT SHEET · 官网岗位事实页</p>
        <h3>{match.jobTitle}</h3>
        <p>{match.organizationName} · {match.eventTitle}</p>
      </div>
      <button type="button" className="official-detail-close" onClick={onClose} aria-label="关闭官网岗位详情">×</button>
    </header>

    <div className={`official-verification-strip ${verified ? 'verified' : 'pending'}`}>
      <strong>{verified ? '官网关键事实已核实' : '官网事实已整理，仍有关键项待核实'}</strong>
      <span>{verified ? '公告正文与岗位附件已交叉归档' : match.qualitySummary?.missingFields.length
        ? `缺少 ${match.qualitySummary.missingFields.length} 项官网字段证据，系统不会猜测`
        : '未补齐的内容不会被系统猜测'}</span>
    </div>

    <section className="official-detail-section">
      <p className="official-detail-kicker">岗位结论</p>
      <section className={match.employmentIdentityConfirmed ? 'official-identity confirmed' : 'official-identity pending'}>
        <span>用工性质</span>
        <strong>{identity}</strong>
        <small>{match.employmentStatement
          ?? '系统只按公告原文判断用工身份，不根据单位名称推断。'}</small>
      </section>
      {match.employmentType === 'PUBLIC_INSTITUTION_FORMAL' &&
        <p className="official-identity-note">公告明确录用后签订事业单位聘用合同；公告未单列是否占用事业编制。</p>}
      <dl className="official-facts">
        <div><dt>主管部门</dt><dd>{match.supervisingDepartment ?? '官网未明确'}</dd></div>
        <div><dt>岗位代码</dt><dd>{match.externalJobCode ?? '官网未明确'}</dd></div>
        <div><dt>岗位类别</dt><dd>{match.jobCategory ?? '官网未明确'}</dd></div>
        <div><dt>系统技术方向</dt><dd>{familyLabels[match.jobFamily]}</dd></div>
        <div><dt>岗位等级</dt><dd>{match.jobGrade ?? '官网未明确'}</dd></div>
        <div><dt>招聘人数</dt><dd>{headcountMissing ? '官网未明确' : `${match.headcount} 人`}</dd></div>
        <div><dt>工作地点</dt><dd>{match.location || '官网未明确'}</dd></div>
      </dl>
    </section>

    <section className="official-detail-section">
      <p className="official-detail-kicker">报考条件</p>
      <dl className="official-facts official-condition-grid">
        <div><dt>学历</dt><dd>{educationText}</dd></div>
        <div><dt>学位</dt><dd>{match.degreeRequirement ?? '官网未明确'}</dd></div>
        <div className="wide"><dt>专业</dt><dd>{majorText}</dd></div>
        <div><dt>年龄</dt><dd>{ageText}{match.ageReferenceDate ? `（计算至 ${match.ageReferenceDate}）` : ''}</dd></div>
        <div><dt>性别</dt><dd>{match.genderRequirement ?? '官网未明确'}</dd></div>
        <div><dt>人员范围</dt><dd>{match.candidateScope ?? '官网未明确'}</dd></div>
        <div><dt>工作经历</dt><dd>{match.minimumExperienceYears === null ? '官网未明确' : `${match.minimumExperienceYears} 年及以上`}</dd></div>
        <div><dt>职称</dt><dd>{factList(match.requiredProfessionalTitles, '官网未明确')}</dd></div>
        {match.otherRequirements && <div className="wide"><dt>其他条件</dt><dd>{match.otherRequirements}</dd></div>}
      </dl>
      <div className="official-rule-stack">
        {match.graduateRule && <p><strong>应届生范围</strong><span>{match.graduateRule}</span></p>}
        {match.overseasDegreeRule && <p><strong>境外学历</strong><span>{match.overseasDegreeRule}</span></p>}
        {match.experienceEvidenceRule && <p><strong>经历证明</strong><span>{match.experienceEvidenceRule}</span></p>}
      </div>
    </section>

    <section className="official-detail-section">
      <p className="official-detail-kicker">招聘流程</p>
      <ol className="official-process">
        <li><span>公告发布</span><strong>{match.publishedOn ?? '官网未明确'}</strong></li>
        <li><span>网上报名</span><strong>{applicationPeriod(match)}</strong>
          {match.registrationUrl && <a href={match.registrationUrl} target="_blank" rel="noreferrer">打开报名入口</a>}</li>
        <li><span>资格初审</span><strong>{officialTime(match.qualificationReviewEndsOn) ? `截至 ${officialTime(match.qualificationReviewEndsOn)}` : '官网未明确'}</strong></li>
        <li><span>缴费确认</span><strong>{officialTime(match.paymentEndsOn) ? `截至 ${officialTime(match.paymentEndsOn)}` : '官网未明确'}</strong></li>
        <li><span>准考证</span><strong>{dateRange(match.admissionTicketStartsOn, match.admissionTicketEndsOn)}</strong></li>
        <li><span>笔试</span><strong>{match.writtenExamOn ?? '官网未明确'}</strong>
          <small>{factList(match.writtenExamSubjects, '官网未明确')}</small></li>
        <li><span>面试</span><strong>{match.interviewRatio ? `入围比例 ${match.interviewRatio}` : '官网未明确'}</strong>
          {match.professionalTestRequired && <small>含专业知识测试</small>}
          {match.interviewRule && <small>{match.interviewRule}</small>}</li>
      </ol>
      {match.contactPhone && <p className="official-contact">招聘咨询：<a href={`tel:${match.contactPhone}`}>{match.contactPhone}</a></p>}
    </section>

    <section className="official-detail-section">
      <p className="official-detail-kicker">岗位说明</p>
      <p className="official-duties">{match.duties
        ?? '官网岗位表未单列日常职责；系统保留岗位名称、类别及全部报考条件，不自行补写职责。'}</p>
      {match.originalRequirementText && <details className="official-original-text">
        <summary>查看岗位表原始条件</summary><p>{match.originalRequirementText}</p>
      </details>}
    </section>

    {match.warnings.length > 0 && <section className="official-review-notes">
      <p className="official-detail-kicker">与你的画像有关的提醒</p>
      <ul>{match.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
    </section>}

    {evidenceReferences.length > 0 && <section className="official-detail-section official-evidence-chain">
      <p className="official-detail-kicker">字段证据链</p>
      <ul>{evidenceReferences.map((evidence, index) => <li key={`${evidence.fieldName}-${index}`}>
        <strong>{evidence.fieldName}</strong>
        <span>{evidence.excerpt ?? '官网原文片段未保存'}</span>
        <small>{evidence.locator ?? evidence.sourceTitle ?? '官网来源'}</small>
      </li>)}</ul>
    </section>}

    <footer>
      <div>
        <strong>官网证据</strong>
        <p>本页事实来自公告正文与岗位附件；链接保留用于复核，不要求你跳出系统才能看懂岗位。</p>
      </div>
      <nav aria-label="官网证据链接">
        <a href={match.sourceUrl} target="_blank" rel="noreferrer">查看官方公告</a>
        <a href={match.attachmentSourceUrl} target="_blank" rel="noreferrer">查看岗位附件</a>
      </nav>
    </footer>
  </aside>
}
