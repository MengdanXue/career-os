import type { EligibilityStatus, OpportunityTier } from '../api/contracts'

type Props = {
  eligibility: EligibilityStatus
  coveragePercent: number | null
  tier: OpportunityTier
  deadline: string | null
}

const eligibilityLabels: Record<EligibilityStatus, string> = {
  ELIGIBLE: '资格通过',
  CONDITIONAL: '资格有条件通过',
  NEEDS_CONFIRMATION: '资格待核实',
  CONFLICTING_EVIDENCE: '官方证据冲突',
  INELIGIBLE: '资格不通过',
  UNKNOWN: '资格待核实',
}

const tierLabels: Record<OpportunityTier, string> = {
  T1: 'T1 优先关注',
  T2: 'T2 值得评估',
  T3: 'T3 备选观察',
  REVIEW: '需要人工复核',
  EXCLUDED: '已排除',
}

export function DecisionRail({ eligibility, coveragePercent, tier, deadline }: Props) {
  return (
    <ol className="decision-rail" aria-label="决策路径">
      <li data-state={eligibility.toLowerCase()}><span>资格</span><strong>{eligibilityLabels[eligibility]}</strong></li>
      <li data-state={coveragePercent === null ? 'unknown' : 'known'}>
        <span>证据</span><strong>{coveragePercent === null ? '证据覆盖待核实' : `证据覆盖 ${coveragePercent}%`}</strong>
      </li>
      <li data-state={tier.toLowerCase()}><span>层级</span><strong>{tierLabels[tier]}</strong></li>
      <li data-state={deadline ? 'known' : 'unknown'}><span>截止</span><strong>{deadline ? `截止 ${deadline}` : '截止时间待核实'}</strong></li>
    </ol>
  )
}
