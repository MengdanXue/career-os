import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { StatusChip } from '../../components/StatusChip'
import { watchJob } from '../today/watchlistApi'
import type { EligibilityStatus } from './confirmationApi'
import type { AgentResponse, PendingConfirmation } from './agentApi'
import { ApiProblem } from '../../api/http'
import { PendingConfirmations } from './PendingConfirmations'

/**
 * 关注入口。之前关注清单只有接口没有入口，任何岗位都进不去，整个功能不可达。
 *
 * <p>基准取这一行屏幕上显示的结论，不让服务端重算——重算可能给出另一个答案，
 * 那就不是用户看到的东西了。
 */
function WatchButton({ candidateId, jobId, seenStatus, evaluatorVersion }: {
  candidateId: string; jobId: string; seenStatus: EligibilityStatus; evaluatorVersion: string | null
}) {
  const client = useQueryClient()
  const watch = useMutation({
    mutationFn: () => watchJob(candidateId, jobId, seenStatus, evaluatorVersion),
    onSuccess: () => client.invalidateQueries({ queryKey: ['watchlist', candidateId] }),
  })
  if (watch.isSuccess) return <span className="watch-added">已加入关注</span>
  return <><button type="button" className="watch-add" disabled={watch.isPending}
    onClick={() => watch.mutate()}>关注</button>
    {watch.isError && <span role="alert">{watch.error instanceof ApiProblem ? watch.error.message : '关注没有成功，请重试。'}</span>}</>
}

export function AgentAnswer({ result, candidateId, pendingItems, confirmationsDisabled = false, onConfirmed }: {
  result: AgentResponse
  candidateId: string
  pendingItems?: PendingConfirmation[]
  confirmationsDisabled?: boolean
  onConfirmed?: () => Promise<void>
}) {
  const violations = result.violations ?? []
  const pending = pendingItems ?? result.pendingConfirmations ?? []
  return <section className="agent-answer" aria-live="polite">
    {result.fallbackUsed && <div className="local-answer-note">
      <p>模型叙述未通过校验，以下为程序生成的确定性结论</p>
      {violations.length > 0 && <ul className="answer-violations">{violations.map(reason => <li key={reason}>{reason}</li>)}</ul>}
    </div>}
    <p className="answer-text">{result.answer}</p>
    <p className="answer-version">展示结论资料版本：<code data-testid="display-profile-version">{result.profileVersion ?? '未知'}</code></p>
    {result.decisions.length > 0 && <ul>{result.decisions.map(decision => <li key={decision.decisionId} data-job-id={decision.jobId} data-decision-id={decision.decisionId}><StatusChip tone={decision.tier === 'T1' ? 'positive' : 'neutral'}>{decision.tier}</StatusChip><span><strong>{decision.jobTitle}</strong><small>{decision.organizationName} · 适配 {decision.fit.score} · 稳定 {decision.stability.score}</small><small>展示评估版本：<code>{decision.evaluatorVersion ?? '未知'}</code></small></span><span className="decision-actions"><WatchButton key={decision.jobId} candidateId={candidateId} jobId={decision.jobId} seenStatus={decision.eligibilityStatus as EligibilityStatus} evaluatorVersion={decision.evaluatorVersion ?? null} /><Link to={`/opportunities/${decision.jobId}?tier=${decision.tier}`} aria-label={`查看 ${decision.jobTitle}`}>查看档案 →</Link></span></li>)}</ul>}
    {result.sessionId && <PendingConfirmations key={result.sessionId} candidateId={candidateId}
      sessionId={result.sessionId} items={pending} disabled={confirmationsDisabled} onConfirmed={onConfirmed} />}
    <p className="agent-disclaimer">{result.disclaimer}</p>
  </section>
}
