import type { JobDecision } from './opportunityApi'
import { OpportunityRow } from './OpportunityRow'

export function OpportunityQueue({ decisions, selectedId, onOpen }: { decisions: JobDecision[]; selectedId?: string; onOpen: (decision: JobDecision) => void }) {
  if (decisions.length === 0) return <div className="queue-empty"><strong>这一层暂时没有岗位</strong><p>岗位更新或资料变化后，Career OS 会重新计算。</p></div>
  return <div className="opportunity-queue" aria-label="岗位列表">{decisions.map(decision => <OpportunityRow key={decision.decisionId} decision={decision} active={selectedId === decision.decisionId} onOpen={() => onOpen(decision)} />)}</div>
}
