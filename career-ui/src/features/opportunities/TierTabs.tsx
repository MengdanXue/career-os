import type { OpportunityTier } from '../../api/contracts'

const tabs: { tier: OpportunityTier; label: string; description: string }[] = [
  { tier: 'T1', label: 'T1 优先', description: '资格与稳定性最值得关注' },
  { tier: 'T2', label: 'T2 评估', description: '有价值，也有待核实项' },
  { tier: 'T3', label: 'T3 备选', description: '暂不优先，持续观察' },
  { tier: 'EXCLUDED', label: '排除 / 复核', description: '硬条件不符或需人工判断' },
]

export function TierTabs({ value, onChange }: { value: OpportunityTier; onChange: (tier: OpportunityTier) => void }) {
  return <div className="tier-tabs" role="tablist" aria-label="机会层级">{tabs.map(tab => <button key={tab.tier} role="tab" aria-selected={value === tab.tier} type="button" onClick={() => onChange(tab.tier)}><strong>{tab.label}</strong><small>{tab.description}</small></button>)}</div>
}
