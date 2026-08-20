import type { JobLibrarySummary } from '../../api/contracts'
import type { JobDecision } from './opportunityApi'
import { OpportunityRow } from './OpportunityRow'

export function OpportunityQueue({ decisions, admissionSummary, selectedId, onOpen }: { decisions: JobDecision[]; admissionSummary?: JobLibrarySummary; selectedId?: string; onOpen: (decision: JobDecision) => void }) {
  if (decisions.length === 0 && admissionSummary?.opportunityReady === 0) return <div className="queue-empty admission-empty">
    <p className="section-number">TRUSTED POOL · 0</p>
    <strong>目前没有通过证据准入的可信岗位</strong>
    <p>原始岗位不会自动进入 T1/T2/T3</p>
    <small>岗位库中的 {admissionSummary.total} 条记录仍然保留。请先完成目标岗位、用工身份和资格证据复核。</small>
  </div>
  if (decisions.length === 0) return <div className="queue-empty"><strong>这一层暂时没有岗位</strong><p>已有可信岗位，但当前层级没有结果。可以切换其他层级查看。</p></div>
  return <div className="opportunity-queue" aria-label="岗位列表">{decisions.map(decision => <OpportunityRow key={decision.decisionId} decision={decision} active={selectedId === decision.decisionId} onOpen={() => onOpen(decision)} />)}</div>
}
