import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { OpportunityTier } from '../../api/contracts'
import { queryKeys } from '../../api/http'
import { AsyncState } from '../../components/AsyncState'
import { JobDossier } from './JobDossier'
import { listDecisions, type JobDecision } from './opportunityApi'
import { OpportunityQueue } from './OpportunityQueue'
import { searchWithTier, tierFromSearch } from './opportunityFilters'
import { TierTabs } from './TierTabs'

export function OpportunitiesPage({ initialTier }: { initialTier?: OpportunityTier }) {
  const location = useLocation()
  const navigate = useNavigate()
  const [tier, setTier] = useState<OpportunityTier>(() => initialTier ?? tierFromSearch(location.search))
  const [selected, setSelected] = useState<JobDecision | null>(null)
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const decisions = useQuery({ queryKey: queryKeys.decisions(candidateId, { tier, page: 0 }), queryFn: () => listDecisions(candidateId, tier) })

  function changeTier(next: OpportunityTier) {
    setTier(next)
    setSelected(null)
    navigate({ search: searchWithTier(location.search, next) }, { replace: true })
  }

  return <main className="opportunities-page page-frame wide-frame">
    <div className="page-heading"><div><p className="eyebrow">OPPORTUNITY LEDGER · 机会账本</p><h1>机会池</h1><p className="page-intro">层级互不混排。先判断能不能报，再比较适配度与长期稳定性。</p></div><p className="ledger-note">当前候选人<br /><strong>{candidateId.slice(0, 8)}</strong></p></div>
    <TierTabs value={tier} onChange={changeTier} />
    <AsyncState loading={decisions.isLoading} error={decisions.error} empty={false}>
      <div className="opportunity-layout"><section><div className="queue-summary"><span>{decisions.data?.total ?? 0} 个岗位</span><span>第 {(decisions.data?.page ?? 0) + 1} 页</span></div><OpportunityQueue decisions={decisions.data?.items ?? []} selectedId={selected?.decisionId} onOpen={setSelected} /></section>{selected ? <JobDossier decision={selected} onClose={() => setSelected(null)} /> : <aside className="dossier-placeholder"><p className="section-number">岗位档案</p><strong>选择一个岗位查看证据链</strong><p>硬性阻断、分项理由、证据覆盖与计算版本会集中显示在这里。</p></aside>}</div>
    </AsyncState>
  </main>
}
