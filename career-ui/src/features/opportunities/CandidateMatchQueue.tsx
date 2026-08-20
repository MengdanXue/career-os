import type { CandidateMatch } from './candidateMatchApi'

const eligibilityLabels: Record<string, string> = {
  ELIGIBLE: '硬条件符合',
  LIKELY_ELIGIBLE: '大概率符合',
  UNCERTAIN: '仍有条件待核实',
}

export function CandidateMatchQueue({ matches, total }: { matches: CandidateMatch[]; total: number }) {
  if (matches.length === 0) return null
  return <section className="candidate-match-section" aria-labelledby="candidate-match-title">
    <header>
      <div>
        <p className="section-number">OFFICIAL WORKBOOK MATCHES · 官网岗位初筛</p>
        <h2 id="candidate-match-title">官网 Excel 初筛：共 {total} 个符合画像，当前展示 {matches.length} 个</h2>
      </div>
      <p>已完成岗位表解析和个人条件比对；用工身份确认后，再进入 T1/T2/T3 决策队列。</p>
    </header>
    <div className="candidate-match-grid">
      {matches.map(match => <article className="candidate-match-card" key={match.jobId}>
        <div className="candidate-match-meta">
          <span>{eligibilityLabels[match.eligibilityStatus] ?? '待核实'}</span>
          <span>{match.location}</span>
        </div>
        <h3>{match.jobTitle}</h3>
        <p className="organization">{match.organizationName}</p>
        <div className="candidate-match-scores">
          <span>适配 <strong>{match.fitScore}</strong></span>
          <span>证据覆盖 <strong>{match.coveragePercent}%</strong></span>
        </div>
        {!match.employmentIdentityConfirmed
          ? <p className="identity-warning">用工身份待确认</p>
          : <p className="identity-confirmed">用工身份已核验</p>}
        <a href={match.sourceUrl} target="_blank" rel="noreferrer">查看官方公告</a>
      </article>)}
    </div>
  </section>
}
