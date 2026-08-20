import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import type { OpportunityTier } from '../../api/contracts'
import { queryKeys } from '../../api/http'
import { AsyncState } from '../../components/AsyncState'
import { getJobLibrarySummary } from '../updates/updateApi'
import { JobDossier } from './JobDossier'
import { listDecisions, type JobDecision } from './opportunityApi'
import { OpportunityQueue } from './OpportunityQueue'
import { searchWithTier, tierFromSearch } from './opportunityFilters'
import { TierTabs } from './TierTabs'

export function OpportunitiesPage({ initialTier }: { initialTier?: OpportunityTier }) {
  const location = useLocation()
  const navigate = useNavigate()
  const { jobId } = useParams()
  const [tier, setTier] = useState<OpportunityTier>(() => initialTier ?? tierFromSearch(location.search))
  const [selected, setSelected] = useState<JobDecision | null>(null)
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const decisions = useQuery({ queryKey: queryKeys.decisions(candidateId, { tier, page: 0 }), queryFn: () => listDecisions(candidateId, tier) })
  const library = useQuery({ queryKey: queryKeys.jobLibrarySummary, queryFn: getJobLibrarySummary })

  useEffect(() => {
    if (!jobId || !decisions.data) return
    const requested = decisions.data.items.find(decision => decision.jobId === jobId)
    if (requested && requested.decisionId !== selected?.decisionId) setSelected(requested)
  }, [decisions.data, jobId, selected?.decisionId])

  function changeTier(next: OpportunityTier) {
    setTier(next)
    setSelected(null)
    navigate({ pathname: '/opportunities', search: searchWithTier(location.search, next) }, { replace: true })
  }

  function openDossier(decision: JobDecision) {
    setSelected(decision)
    navigate({ pathname: `/opportunities/${decision.jobId}`, search: searchWithTier(location.search, tier) })
  }

  function closeDossier() {
    setSelected(null)
    navigate({ pathname: '/opportunities', search: searchWithTier(location.search, tier) }, { replace: true })
  }

  return <main className="opportunities-page page-frame wide-frame">
    <div className="page-heading">
      <div><p className="eyebrow">OPPORTUNITY LEDGER · 机会账本</p><h1>机会池</h1><p className="page-intro">只展示通过证据准入的岗位。先判断能不能报，再比较适配度与长期稳定性。</p></div>
      <p className="ledger-note">当前候选人<br /><strong>{candidateId.slice(0, 8)}</strong>{library.data ? <small>{library.data.opportunityReady} / {library.data.total} 个可信机会</small> : null}</p>
    </div>
    <TierTabs value={tier} onChange={changeTier} />
    <AsyncState loading={decisions.isLoading || library.isLoading} error={decisions.error ?? library.error} empty={false}>
      <div className="opportunity-layout">
        <section>
          <div className="queue-summary"><span>{decisions.data?.total ?? 0} 个岗位</span><span>第 {(decisions.data?.page ?? 0) + 1} 页</span></div>
          <OpportunityQueue decisions={decisions.data?.items ?? []} admissionSummary={library.data} selectedId={selected?.decisionId} onOpen={openDossier} />
        </section>
        {selected
          ? <JobDossier decision={selected} onClose={closeDossier} />
          : <aside className="dossier-placeholder"><p className="section-number">岗位档案</p><strong>选择一个岗位查看证据链</strong><p>硬性阻断、分项理由、证据覆盖与计算版本会集中显示在这里。</p></aside>}
      </div>
    </AsyncState>
  </main>
}
