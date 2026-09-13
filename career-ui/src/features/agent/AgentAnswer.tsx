import { useMutation } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { StatusChip } from '../../components/StatusChip'
import { watchJob } from '../today/watchlistApi'
import type { EligibilityStatus } from './confirmationApi'
import type { AgentResponse } from './agentApi'
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
  const watch = useMutation({ mutationFn: () => watchJob(candidateId, jobId, seenStatus, evaluatorVersion) })
  if (watch.isSuccess) return <span className="watch-added">已加入关注</span>
  return <button type="button" className="watch-add" disabled={watch.isPending}
    onClick={() => watch.mutate()}>关注</button>
}

export function AgentAnswer({ result, candidateId }: { result: AgentResponse; candidateId: string }) {
  const violations = result.violations ?? []
  const pending = result.pendingConfirmations ?? []
  return <section className="agent-answer" aria-live="polite">
    {result.fallbackUsed && <div className="local-answer-note">
      <p>模型叙述未通过校验，以下为程序生成的确定性结论</p>
      {violations.length > 0 && <ul className="answer-violations">{violations.map(reason => <li key={reason}>{reason}</li>)}</ul>}
    </div>}
    <p className="answer-text">{result.answer}</p>
    {result.decisions.length > 0 && <ul>{result.decisions.map(decision => <li key={decision.decisionId}><StatusChip tone={decision.tier === 'T1' ? 'positive' : 'neutral'}>{decision.tier}</StatusChip><span><strong>{decision.jobTitle}</strong><small>{decision.organizationName} · 适配 {decision.fit.score} · 稳定 {decision.stability.score}</small></span><span className="decision-actions"><WatchButton candidateId={candidateId} jobId={decision.jobId} seenStatus={decision.eligibilityStatus as EligibilityStatus} evaluatorVersion={decision.evaluatorVersion ?? null} /><Link to={`/opportunities/${decision.jobId}?tier=${decision.tier}`} aria-label={`查看 ${decision.jobTitle}`}>查看档案 →</Link></span></li>)}</ul>}
    {result.sessionId && pending.length > 0 &&
      <PendingConfirmations candidateId={candidateId} sessionId={result.sessionId} items={pending} />}
    <p className="agent-disclaimer">{result.disclaimer}</p>
  </section>
}
