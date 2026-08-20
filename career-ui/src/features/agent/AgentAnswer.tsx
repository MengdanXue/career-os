import { Link } from 'react-router-dom'
import { StatusChip } from '../../components/StatusChip'
import type { AgentResponse } from './agentApi'

export function AgentAnswer({ result }: { result: AgentResponse }) {
  return <section className="agent-answer" aria-live="polite">
    {result.fallbackUsed && <p className="local-answer-note">已使用本地决策规则完成回答</p>}
    <p className="answer-text">{result.answer}</p>
    {result.decisions.length > 0 && <ul>{result.decisions.map(decision => <li key={decision.decisionId}><StatusChip tone={decision.tier === 'T1' ? 'positive' : 'neutral'}>{decision.tier}</StatusChip><span><strong>{decision.jobTitle}</strong><small>{decision.organizationName} · 适配 {decision.fit.score} · 稳定 {decision.stability.score}</small></span><Link to={`/opportunities/${decision.jobId}?tier=${decision.tier}`} aria-label={`查看 ${decision.jobTitle}`}>查看档案 →</Link></li>)}</ul>}
    <p className="agent-disclaimer">{result.disclaimer}</p>
  </section>
}
