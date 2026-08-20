import { DecisionRail } from '../../components/DecisionRail'
import { ScoreBar } from '../../components/ScoreBar'
import { DimensionList } from './DimensionList'
import type { JobDecision } from './opportunityApi'

export function JobDossier({ decision, onClose }: { decision: JobDecision; onClose?: () => void }) {
  const evidenceCoverage = Math.min(decision.fit.coveragePercent, decision.stability.coveragePercent)
  return <aside className="job-dossier" aria-label={`${decision.jobTitle} 岗位档案`}>
    <header><div><p className="eyebrow">JOB DOSSIER · 岗位档案</p><h2>{decision.jobTitle}</h2><p>{decision.organizationName} · {decision.location}</p></div>{onClose && <button className="dossier-close" type="button" onClick={onClose} aria-label="关闭岗位档案">×</button>}</header>
    <DecisionRail eligibility={decision.eligibilityStatus} coveragePercent={evidenceCoverage} tier={decision.tier} deadline={null} />
    <section className="blocker-section" aria-labelledby="blocker-title"><p className="section-number">01 · HARD GATE</p><h3 id="blocker-title">硬性资格先看</h3>{decision.warnings.length ? <ul>{decision.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul> : <p className="positive-note">目前没有发现明确的硬性阻断项。</p>}</section>
    <section aria-labelledby="fit-title"><p className="section-number">02 · FIT</p><h3 id="fit-title">岗位适配</h3><ScoreBar label="综合适配" score={decision.fit.score} /><DimensionList dimensions={decision.fit.dimensions} /></section>
    <section aria-labelledby="stability-title"><p className="section-number">03 · STABILITY</p><h3 id="stability-title">稳定性</h3><ScoreBar label="稳定性" score={decision.stability.score} /><DimensionList dimensions={decision.stability.dimensions} /></section>
    <section className="audit-strip" aria-label="决策审计信息"><span>规则 {decision.evaluatorVersion}</span><span>资料 {decision.profileVersion}</span><span>评估 {new Date(decision.assessedAt).toLocaleString('zh-CN')}</span></section>
    <p className="decision-disclaimer">{decision.disclaimer}</p>
  </aside>
}
