import { StatusChip } from '../../components/StatusChip'
import type { JobDecision } from './opportunityApi'

export function OpportunityRow({ decision, active, onOpen }: { decision: JobDecision; active: boolean; onOpen: () => void }) {
  const negative = decision.eligibilityStatus.includes('INELIGIBLE')
  return <article className="opportunity-row" data-active={active}>
    <button type="button" aria-label={`查看 ${decision.jobTitle}`} onClick={onOpen}>
      <span className="row-top"><StatusChip tone={negative ? 'negative' : decision.eligibilityStatus === 'UNCERTAIN' ? 'warning' : 'positive'}>{negative ? '资格不符' : decision.eligibilityStatus === 'UNCERTAIN' ? '待核实' : '资格通过'}</StatusChip><span>{decision.location}</span></span>
      <strong>{decision.jobTitle}</strong>
      <span className="organization">{decision.organizationName}</span>
      <span className="row-scores"><b>适配 {decision.fit.score}</b><b>稳定 {decision.stability.score}</b><b>证据 {Math.min(decision.fit.coveragePercent, decision.stability.coveragePercent)}%</b></span>
    </button>
  </article>
}
