import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { queryKeys } from '../../api/http'
import { AsyncState } from '../../components/AsyncState'
import { getWorkbenchSummary, type WorkbenchSummary } from './todayApi'

function changedJobCount(summary: WorkbenchSummary) {
  return summary.changes.items.reduce((total, item) => total + ['inserted', 'updated', 'deactivated', 'added'].reduce((count, key) => {
    const value = item.jobDeltaSummary[key]
    return count + (typeof value === 'number' ? value : 0)
  }, 0), 0)
}

export function TodayPage() {
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const summary = useQuery({ queryKey: queryKeys.workbench(candidateId), queryFn: () => getWorkbenchSummary(candidateId) })
  const data = summary.data
  const categories = data ? [data.deadlines.length > 0, data.changes.items.length > 0, data.sources.issues.length > 0, data.reviews.pending > 0].filter(Boolean).length : 0

  return <main className="today-page page-frame wide-frame">
    <AsyncState loading={summary.isLoading} error={summary.error}>
      {data && <>
        <header className="today-heading"><div><p className="eyebrow">DAILY BRIEF · 今日简报</p><h1>{categories ? `今天要处理 ${categories} 类事项` : '今天没有必须处理的变化'}</h1><p className="page-intro">只呈现与选择有关的变化。没有新事项时，你不需要重复检查同一批岗位。</p></div><time dateTime={data.generatedAt}>更新于 {new Date(data.generatedAt).toLocaleString('zh-CN')}</time></header>
        <section className="tier-ledger" aria-label="机会层级概览">
          {[['T1', data.tierCounts.t1, '优先'], ['T2', data.tierCounts.t2, '评估'], ['T3', data.tierCounts.t3, '观察'], ['EXCLUDED', data.tierCounts.excluded, '排除 / 复核']].map(([tier, count, label]) => <Link key={String(tier)} to={`/opportunities?tier=${tier}`} aria-label={`查看 ${tier}`}><span>{tier}</span><strong>{count}</strong><small>{label}</small></Link>)}
        </section>
        {categories === 0 ? <section className="calm-state"><p className="section-number">ALL CLEAR</p><h2>保持当前节奏即可</h2><p>数据源正常，近期没有岗位变更、临近截止或待复核事项。你仍可查看机会池或主动更新岗位库。</p><div><Link to="/opportunities?tier=T1">查看 T1 机会</Link><Link to="/updates">检查岗位来源</Link></div></section> : <div className="attention-grid">
          {data.deadlines.length > 0 && <section className="attention-card urgent"><header><p className="section-number">01 · DEADLINE</p><span>{data.deadlines.length} 项</span></header><h2>临近截止</h2><ul>{data.deadlines.map(item => <li key={item.jobId}><div><strong>{item.jobTitle}</strong><small>{item.organizationName} · {item.location}</small></div><span><b>{item.daysRemaining}</b> 天</span></li>)}</ul><Link to="/opportunities?tier=T1">进入机会池 →</Link></section>}
          {data.changes.items.length > 0 && <section className="attention-card"><header><p className="section-number">02 · CHANGE</p><span>{data.changes.items.length} 批</span></header><h2>{changedJobCount(data)} 个岗位发生变更</h2><p>新增、修改或下线只在变化时提醒，不会把同一岗位重复当成新增。</p><Link to="/updates">查看更新记录 →</Link></section>}
          {data.sources.issues.length > 0 && <section className="attention-card warning"><header><p className="section-number">03 · SOURCE</p><span>{data.sources.healthy}/{data.sources.enabled} 正常</span></header><h2>数据源需要检查</h2><ul>{data.sources.issues.map(issue => <li key={issue.id}>{issue.name}连续失败 {issue.consecutiveFailureCount} 次</li>)}</ul><Link to="/updates">处理数据源 →</Link></section>}
          {data.reviews.pending > 0 && <section className="attention-card"><header><p className="section-number">04 · REVIEW</p><span>{data.reviews.pending} 条</span></header><h2>{data.reviews.pending} 条数据等待你复核</h2><p>确认公告抽取结果后，它们才会进入岗位决策。</p><Link to="/updates?view=reviews">开始复核 →</Link></section>}
        </div>}
        {(!data.changes.available || !data.sources.available || !data.reviews.available) && <section className="partial-warning" role="status"><strong>部分信息暂不可用</strong><p>{[data.changes.message, data.sources.message, data.reviews.message].filter(Boolean).join('；')}</p></section>}
      </>}
    </AsyncState>
  </main>
}
