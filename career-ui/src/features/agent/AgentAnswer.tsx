import { Link } from 'react-router-dom'
import { StatusChip } from '../../components/StatusChip'
import type { AgentResponse } from './agentApi'
import { PendingConfirmations } from './PendingConfirmations'

export function AgentAnswer({ result, candidateId }: { result: AgentResponse; candidateId: string }) {
  const violations = result.violations ?? []
  const pending = result.pendingConfirmations ?? []
  return <section className="agent-answer" aria-live="polite">
    {result.fallbackUsed && <div className="local-answer-note">
      <p>模型叙述未通过校验，以下为程序生成的确定性结论</p>
      {violations.length > 0 && <ul className="answer-violations">{violations.map(reason => <li key={reason}>{reason}</li>)}</ul>}
    </div>}
    <p className="answer-text">{result.answer}</p>
    {result.decisions.length > 0 && <ul>{result.decisions.map(decision => <li key={decision.decisionId}><StatusChip tone={decision.tier === 'T1' ? 'positive' : 'neutral'}>{decision.tier}</StatusChip><span><strong>{decision.jobTitle}</strong><small>{decision.organizationName} · 适配 {decision.fit.score} · 稳定 {decision.stability.score}</small></span><Link to={`/opportunities/${decision.jobId}?tier=${decision.tier}`} aria-label={`查看 ${decision.jobTitle}`}>查看档案 →</Link></li>)}</ul>}
    {result.sessionId && pending.length > 0 &&
      <PendingConfirmations candidateId={candidateId} sessionId={result.sessionId} items={pending} />}
    <p className="agent-disclaimer">{result.disclaimer}</p>
  </section>
}
