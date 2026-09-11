import { StatusChip } from '../../components/StatusChip'
import type { JobDecision } from './opportunityApi'

// 硬资格只有三种呈现：明确不符、明确通过、以及“还没定”。后者不再细分成
// “大概率通过/不通过”——那种说法会让证据不足看起来像一个带倾向的结论。
const chips: Record<string, { tone: 'negative' | 'warning' | 'positive'; label: string }> = {
  INELIGIBLE: { tone: 'negative', label: '资格不符' },
  ELIGIBLE: { tone: 'positive', label: '资格通过' },
  CONDITIONAL: { tone: 'warning', label: '条件通过' },
  NEEDS_CONFIRMATION: { tone: 'warning', label: '待核实' },
  CONFLICTING_EVIDENCE: { tone: 'warning', label: '证据冲突' },
}

export function OpportunityRow({ decision, active, onOpen }: { decision: JobDecision; active: boolean; onOpen: () => void }) {
  const chip = chips[decision.eligibilityStatus] ?? { tone: 'warning' as const, label: '待核实' }
  return <article className="opportunity-row" data-active={active}>
    <button type="button" aria-label={`查看 ${decision.jobTitle}`} onClick={onOpen}>
      <span className="row-top"><StatusChip tone={chip.tone}>{chip.label}</StatusChip><span>{decision.location}</span></span>
      <strong>{decision.jobTitle}</strong>
      <span className="organization">{decision.organizationName}</span>
      <span className="row-scores"><b>适配 {decision.fit.score}</b><b>稳定 {decision.stability.score}</b><b>证据 {Math.min(decision.fit.coveragePercent, decision.stability.coveragePercent)}%</b></span>
    </button>
  </article>
}
