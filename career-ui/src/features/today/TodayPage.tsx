import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { queryKeys } from '../../api/http'
import { AsyncState } from '../../components/AsyncState'
import { getPersonalActions, getWorkbenchSummary, type PersonalAction } from './todayApi'
import { WatchlistPanel } from './WatchlistPanel'

const actionLabels: Record<PersonalAction['kind'], string> = {
  CURRENT_JOB_DEADLINE: '当前岗位截止',
  CANDIDATE_EVIDENCE: '资格证据',
  TARGET_JOB_CHANGE: '目标岗位变化',
  APPLICATION_NEXT_STEP: '申请下一步',
  PREPARATION_TIMELINE: '准备时间线',
}

const evidenceLabels: Record<PersonalAction['evidenceStrength'], string> = {
  NONE: '待补证据',
  SELF_REPORTED: '本人信息',
  DOCUMENTED: '官方或材料证据',
  VERIFIED: '已核验',
}

function localIsoDate() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

function formatDue(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  return `${year}年${month}月${day}日`
}

function ActionRow({ action, index }: { action: PersonalAction; index: number }) {
  return <article className="personal-action" data-kind={action.kind.toLowerCase()}>
    <div className="action-order" aria-hidden="true">{String(index + 1).padStart(2, '0')}</div>
    <div className="action-body">
      <header>
        <span>{actionLabels[action.kind]}</span>
        <span>{evidenceLabels[action.evidenceStrength]}</span>
      </header>
      <h2>{action.title}</h2>
      <p>{action.reason}</p>
      <div className="action-facts">
        {action.affectedObjectCount > 0 && <span>影响 {action.affectedObjectCount} 个对象</span>}
        {action.dueOn && <time dateTime={action.dueOn}>官方截止 {formatDue(action.dueOn)}</time>}
      </div>
    </div>
    <Link className="action-link" to={action.deepLink}>现在处理</Link>
  </article>
}

export function TodayPage() {
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const asOf = localIsoDate()
  const actions = useQuery({
    queryKey: queryKeys.personalActions(candidateId, asOf),
    queryFn: () => getPersonalActions(candidateId, asOf),
  })
  const workbench = useQuery({
    queryKey: queryKeys.workbench(candidateId),
    queryFn: () => getWorkbenchSummary(candidateId),
  })
  const data = actions.data
  const itemCount = data?.items.length ?? 0

  return <main className="today-page page-frame wide-frame">
    <AsyncState loading={actions.isLoading} error={actions.error}>
      {data && <>
        <header className="today-heading">
          <div>
            <p className="eyebrow">PERSONAL DOCKET · 今日行动</p>
            <h1>{itemCount > 0 ? `今天最重要的 ${itemCount} 件事` : '今天没有新的资格、截止或准备事项'}</h1>
            <p className="page-intro">顺序已经按截止日期、资格影响和岗位变化排好。先完成第一项，再处理下一项。</p>
          </div>
          <time dateTime={data.asOf}>决策日期 {formatDue(data.asOf)}</time>
        </header>

        {!data.available && data.message && <section className="partial-warning" role="status">
          <strong>部分个人结论暂时无法更新</strong><p>{data.message}</p>
        </section>}

        {itemCount > 0 ? <section className="personal-action-list" aria-label="今日个人行动">
          {data.items.map((action, index) => <ActionRow key={action.id} action={action} index={index} />)}
        </section> : <section className="calm-state">
          <p className="section-number">NO NEW ACTION</p>
          <h2>保持当前节奏即可</h2>
          <p>你可以继续完善资料，或查看当前 T1 机会。</p>
          <div><Link to="/profile">完善我的资料</Link><Link to="/opportunities?tier=T1">查看 T1 机会</Link></div>
        </section>}

        <WatchlistPanel candidateId={candidateId} asOf={asOf} />

        <section className="today-secondary" aria-labelledby="opportunity-overview-title">
          <header><div><p className="eyebrow">OPPORTUNITY LEDGER</p><h2 id="opportunity-overview-title">你的机会概览</h2></div><Link to="/opportunities">查看全部岗位 →</Link></header>
          {workbench.data ? <div className="tier-ledger" aria-label="机会层级概览">
            {[
              ['T1', workbench.data.tierCounts.t1, '优先'],
              ['T2', workbench.data.tierCounts.t2, '评估'],
              ['T3', workbench.data.tierCounts.t3, '观察'],
              ['EXCLUDED', workbench.data.tierCounts.excluded, '排除'],
            ].map(([tier, count, label]) => <Link key={String(tier)} to={`/opportunities?tier=${tier}`} aria-label={`查看 ${tier}`}><span>{tier}</span><strong>{count}</strong><small>{label}</small></Link>)}
          </div> : workbench.isLoading ? <p className="secondary-loading">机会概览正在更新…</p> : <p className="secondary-unavailable">机会概览暂时无法读取，个人行动仍可继续处理。</p>}
        </section>

        <footer className="data-management-entry">
          <div><strong>岗位数据管理</strong><p>官方来源、采集运行和人工复核属于维护工具，不会占用你的个人行动名额。</p></div>
          <Link to="/updates">打开数据管理 →</Link>
        </footer>
      </>}
    </AsyncState>
  </main>
}
