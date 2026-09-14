import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ApiProblem } from '../../api/http'
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
    retry: false,
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
  const changedCount = items.filter(item => item.changedSinceLastSeen).length
  const unreadableCount = items.filter(item => !item.currentStatus).length
  const notRefreshedCount = items.filter(item => item.readState === 'NOT_REFRESHED').length
  const missingBaselineCount = items.filter(item => item.baselineMissing && item.currentStatus).length
  const refreshState = watchlist.isError ? 'failed' : watchlist.isPending ? 'not-read'
    : watchlist.isFetching ? 'refreshing' : 'ready'
  // 只有成功读完、全部可比时，零变化才是一个有证据的结论。
  const heading = refreshState === 'failed' ? '关注清单刷新失败，不能判断是否有变化'
    : refreshState === 'not-read' ? '关注清单尚未读取'
    : refreshState === 'refreshing' ? '正在刷新关注清单'
    : items.length === 0 ? '还没有关注任何岗位'
    : notRefreshedCount > 0 ? `${notRefreshedCount} 个关注岗位尚未刷新，不能当作无变化`
    : unreadableCount > 0 ? `${unreadableCount} 个关注岗位这次无法判断变化`
    : missingBaselineCount > 0 ? `${missingBaselineCount} 个关注岗位尚未建立对照基准`
    : changedCount > 0 ? `${changedCount} 个关注岗位的结论有变化` : '关注岗位暂无结论变化'
  const mutationError = acknowledge.error ?? remove.error

  return <section className="watchlist-panel" aria-labelledby="watchlist-title" data-refresh-state={refreshState}>
    <header>
      <p className="section-number">WATCHLIST · 关注清单</p>
      <h2 id="watchlist-title">{heading}</h2>
      <button type="button" disabled={watchlist.isFetching} onClick={() => void watchlist.refetch()}>刷新关注清单</button>
    </header>
    {watchlist.isError && <p role="alert">{watchlist.error instanceof ApiProblem ? watchlist.error.message : '关注清单没有刷新成功，请重试。'}{watchlist.data ? ' 以下保留上次读取的快照，不代表当前结论。' : ''}</p>}
    {refreshState === 'refreshing' && watchlist.data && <p role="status">正在重新读取；下方仍是上次快照，尚未确认本次变化。</p>}
    {watchlist.dataUpdatedAt > 0 && <p className="watchlist-updated">最近成功读取：<time dateTime={new Date(watchlist.dataUpdatedAt).toISOString()}>{new Date(watchlist.dataUpdatedAt).toLocaleString()}</time></p>}
    {mutationError && <p role="alert">{mutationError instanceof ApiProblem ? mutationError.message : '关注操作没有完成，请重试。'}</p>}
    {refreshState === 'ready' && items.length === 0 && <p className="watchlist-empty">在问答结果或岗位档案里点「关注」，之后这里会显示它们的结论变化和报名截止。</p>}
    <ul>
      {items.map(item => <li key={item.jobPostingId} data-job-id={item.jobPostingId} data-changed={item.changedSinceLastSeen} data-read-state={item.readState}>
        {!item.currentStatus
          ? <div className="watch-unreadable">
              <strong>{item.readState === 'NOT_REFRESHED' ? '这个岗位尚未刷新' : '这个岗位这次读不到结论'}</strong>
              <p>{item.readState === 'NOT_REFRESHED' ? '本次评估预算已用完，没有评估这个岗位，不能判为没有变化。' : '不是没有变化，是没能重新评估。稍后再看一次。'}</p>
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
              <p className="watch-version">展示评估版本：<code>{item.evaluatorVersion ?? '未知'}</code></p>
              <div className="watch-actions">
                <Link to={item.deepLink}>查看档案 →</Link>
                {item.changedSinceLastSeen && <button
                  type="button"
                  disabled={acknowledge.isPending || refreshState !== 'ready'}
                  onClick={() => acknowledge.mutate(item)}
                >知道了</button>}
                {/* 没有基准就发现不了变化，而且这个洞自己不会愈合——必须给一个补基准的入口。 */}
                {item.baselineMissing && <button
                  type="button"
                  disabled={acknowledge.isPending || refreshState !== 'ready'}
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
