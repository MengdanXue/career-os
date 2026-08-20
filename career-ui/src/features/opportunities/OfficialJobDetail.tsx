import type { Ref } from 'react'
import type { CandidateMatch, EducationLevel, EmploymentType, JobFamily } from './candidateMatchApi'

const employmentLabels: Record<EmploymentType, string> = {
  ESTABLISHMENT: '事业编制',
  CONTRACT: '合同制',
  PERSONNEL_AGENCY: '人事代理',
  LABOR_DISPATCH: '劳务派遣',
  PROJECT_BASED: '项目制',
  UNKNOWN: '官网未明确用工性质',
}

const educationLabels: Record<EducationLevel, string> = {
  DOCTORATE: '博士研究生',
  MASTER: '硕士研究生',
  BACHELOR: '本科',
  ASSOCIATE: '大专',
  HIGH_SCHOOL: '高中/中专',
  UNKNOWN: '待核实',
}

const familyLabels: Record<JobFamily, string> = {
  INFORMATION_SYSTEMS: '信息系统',
  DATA: '数据',
  SOFTWARE: '软件研发',
  AI: '人工智能',
  CYBERSECURITY: '网络与安全',
  DIGITALIZATION: '数字化',
  IT_OPERATIONS: 'IT 运维',
  RESEARCH: '科研',
  PRODUCT: '产品',
  OTHER: '其他技术岗位',
}

function factList(values: Array<string | number>, empty = '岗位表未明确') {
  return values.length > 0 ? values.join('、') : empty
}

function applicationPeriod(match: CandidateMatch) {
  if (match.applicationStartsOn && match.applicationEndsOn) {
    return `${match.applicationStartsOn} 至 ${match.applicationEndsOn}`
  }
  if (match.applicationStartsOn) return `自 ${match.applicationStartsOn} 起`
  if (match.applicationEndsOn) return `截至 ${match.applicationEndsOn}`
  return '公告未明确'
}

export function OfficialJobDetail({ match, onClose, detailRef }: { match: CandidateMatch; onClose: () => void; detailRef?: Ref<HTMLElement> }) {
  const identity = match.employmentIdentityConfirmed
    ? (employmentLabels[match.employmentType] ?? match.employmentType)
    : '官网未明确用工性质'

  return <aside id={`official-job-detail-${match.jobId}`} ref={detailRef} tabIndex={-1} className="official-job-detail" aria-label={`${match.jobTitle} 官网岗位详情`}>
    <header>
      <div>
        <p className="section-number">OFFICIAL FACT SHEET · 官网岗位事实页</p>
        <h3>{match.jobTitle}</h3>
        <p>{match.organizationName} · {match.eventTitle}</p>
      </div>
      <button type="button" className="official-detail-close" onClick={onClose} aria-label="关闭官网岗位详情">×</button>
    </header>

    <section className={match.employmentIdentityConfirmed ? 'official-identity confirmed' : 'official-identity pending'}>
      <span>用工性质</span>
      <strong>{identity}</strong>
      {!match.employmentIdentityConfirmed && <small>系统不会根据单位名称推断是否有编制，请以公告或后续资格审查说明为准。</small>}
    </section>

    <section>
      <p className="official-detail-kicker">岗位条件</p>
      <dl className="official-facts">
        <div><dt>岗位代码</dt><dd>{match.externalJobCode || '岗位表未明确'}</dd></div>
        <div><dt>招聘人数</dt><dd>{match.headcount || '岗位表未明确'}</dd></div>
        <div><dt>工作地点</dt><dd>{match.location || '岗位表未明确'}</dd></div>
        <div><dt>岗位方向</dt><dd>{familyLabels[match.jobFamily]}</dd></div>
        <div><dt>最低学历</dt><dd>{educationLabels[match.minimumEducation]}</dd></div>
        <div><dt>年龄要求</dt><dd>{match.maximumAge ? `${match.maximumAge} 周岁${match.ageReferenceDate ? `（以 ${match.ageReferenceDate} 为准）` : ''}` : '岗位表未明确'}</dd></div>
        <div><dt>工作经验</dt><dd>{match.minimumExperienceYears === null ? '岗位表未明确' : `${match.minimumExperienceYears} 年及以上`}</dd></div>
        <div><dt>专业技术职称</dt><dd>{factList(match.requiredProfessionalTitles)}</dd></div>
        <div><dt>毕业年份</dt><dd>{factList(match.acceptedGraduationYears)}</dd></div>
      </dl>
    </section>

    <section>
      <p className="official-detail-kicker">专业要求</p>
      <p className="official-major-list">{factList(match.exactMajors)}</p>
    </section>

    <section>
      <p className="official-detail-kicker">岗位职责</p>
      <p className="official-duties">{match.duties || '岗位表未提供职责说明'}</p>
    </section>

    <section>
      <p className="official-detail-kicker">公告与报名</p>
      <dl className="official-facts compact">
        <div><dt>发布日期</dt><dd>{match.publishedOn ?? '公告未明确'}</dd></div>
        <div><dt>报名时间</dt><dd>{applicationPeriod(match)}</dd></div>
      </dl>
    </section>

    {match.warnings.length > 0 && <section className="official-review-notes">
      <p className="official-detail-kicker">仍需核实</p>
      <ul>{match.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
    </section>}

    <footer>
      <p>以上内容来自官网公告及其岗位附件，页面保留原始出处便于你复核。</p>
      <a href={match.sourceUrl} target="_blank" rel="noreferrer">查看官方公告</a>
    </footer>
  </aside>
}
