import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { statusLabels } from '../agent/confirmationApi'
import { acknowledgeWatchedJob, getWatchlist, unwatchJob, type WatchedJob } from './watchlistApi'

/**
 * 关注清单。
 *
 * <p>这里没有报名按钮，也不会有：系统不代替用户报名，关注只是"我在盯着它"。
 *
 * <p>三处显示规则对应服务端的三条保证：
 *
 * <p>变了的排在前面并标出来，而且刷新页面不会让标记消失——只有用户点"知道了"才消失。
 * 变化信号只有一次，被刷新抹掉就等于没提示过。
 *
 * <p>报名已截止的单独标出，哪怕结论仍是"可报"。只显示"可报"会让人以为还来得及。
 *
 * <p>读不到的说读不到，不显示成上次的结论，也不显示成"没变化"。
 */
export function WatchlistPanel({ candidateId, asOf }: { candidateId: string; asOf: string }) {
  const client = useQueryClient()
  const watchlist = useQuery({
    queryKey: ['watchlist', candidateId, asOf],
    queryFn: () => getWatchlist(candidateId, asOf),
  })

  const acknowledge = useMutation({
    mutationFn: (job: WatchedJob) =>
      acknowledgeWatchedJob(candidateId, job.jobPostingId, job.currentStatus!, job.evaluatorVersion),
    onSuccess: () => client.invalidateQueries({ queryKey: ['watchlist', candidateId] }),
  })
  const remove = useMutation({
    mutationFn: (jobId: string) => unwatchJob(candidateId, jobId),
    onSuccess: () => client.invalidateQueries({ queryKey: ['watchlist', candidateId] }),
  })

  const items = watchlist.data?.items ?? []
  if (items.length === 0) return null
  const changedCount = items.filter(item => item.changedSinceLastSeen).length

  return <section className="watchlist-panel" aria-labelledby="watchlist-title">
    <header>
      <p className="section-number">WATCHLIST · 关注清单</p>
      <h2 id="watchlist-title">
        {changedCount > 0 ? `${changedCount} 个关注岗位的结论有变化` : '关注岗位暂无结论变化'}
      </h2>
    </header>
    <ul>
      {items.map(item => <li key={item.jobPostingId} data-changed={item.changedSinceLastSeen}>
        {!item.currentStatus
          ? <div className="watch-unreadable">
              <strong>这个岗位这次读不到结论</strong>
              <p>不是没有变化，是没能重新评估。稍后再看一次。</p>
            </div>
          : <>
              <div className="watch-headline">
                <strong>{item.jobTitle}</strong>
                <small>{item.organizationName}</small>
              </div>
              <div className="watch-status">
                {item.changedSinceLastSeen && item.lastSeenStatus
                  ? <span className="watch-transition">
                      {statusLabels[item.lastSeenStatus]} → {statusLabels[item.currentStatus]}
                    </span>
                  : <span>{statusLabels[item.currentStatus]}</span>}
                {item.baselineMissing && <span className="watch-nobaseline">尚未开始跟踪变化</span>}
                {item.applicationClosed && <span className="watch-closed">报名已截止</span>}
                {!item.applicationClosed && item.applicationEndsOn &&
                  <time dateTime={item.applicationEndsOn}>报名截止 {item.applicationEndsOn}</time>}
              </div>
              <div className="watch-actions">
                <Link to={item.deepLink}>查看档案 →</Link>
                {item.changedSinceLastSeen && <button
                  type="button"
                  disabled={acknowledge.isPending}
                  onClick={() => acknowledge.mutate(item)}
                >知道了</button>}
                {/* 没有基准就发现不了变化，而且这个洞自己不会愈合——必须给一个补基准的入口。 */}
                {item.baselineMissing && <button
                  type="button"
                  disabled={acknowledge.isPending}
                  onClick={() => acknowledge.mutate(item)}
                >以当前结论为基准，开始跟踪变化</button>}
                <button
                  type="button"
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(item.jobPostingId)}
                >取消关注</button>
              </div>
            </>}
      </li>)}
    </ul>
  </section>
}
