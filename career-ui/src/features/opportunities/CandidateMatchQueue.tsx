import { useEffect, useRef, useState } from 'react'
import type { CandidateMatch } from './candidateMatchApi'
import { OfficialJobDetail } from './OfficialJobDetail'

const eligibilityLabels: Record<string, string> = {
  ELIGIBLE: '硬条件符合',
  LIKELY_ELIGIBLE: '大概率符合',
  UNCERTAIN: '仍有条件待核实',
}

export function CandidateMatchQueue({ matches, total }: { matches: CandidateMatch[]; total: number }) {
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null)
  const selected = matches.find(match => match.jobId === selectedJobId) ?? null
  const detailRef = useRef<HTMLElement>(null)
  const triggerRefs = useRef(new Map<string, HTMLButtonElement>())
  const returnFocusTo = useRef<string | null>(null)

  useEffect(() => {
    if (selectedJobId) {
      returnFocusTo.current = selectedJobId
      detailRef.current?.focus()
    } else if (returnFocusTo.current) {
      triggerRefs.current.get(returnFocusTo.current)?.focus()
      returnFocusTo.current = null
    }
  }, [selectedJobId])
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
      {matches.map(match => <article className="candidate-match-card" data-selected={selectedJobId === match.jobId} key={match.jobId}>
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
        <button type="button" className="candidate-detail-button"
          ref={node => { if (node) triggerRefs.current.set(match.jobId, node); else triggerRefs.current.delete(match.jobId) }}
          onClick={() => setSelectedJobId(match.jobId)} aria-label={`查看 ${match.jobTitle} 完整详情`}
          aria-expanded={selectedJobId === match.jobId} aria-controls={`official-job-detail-${match.jobId}`}>
          站内查看完整详情 <span aria-hidden="true">→</span>
        </button>
      </article>)}
    </div>
    {selected && <OfficialJobDetail match={selected} detailRef={detailRef} onClose={() => setSelectedJobId(null)} />}
  </section>
}
