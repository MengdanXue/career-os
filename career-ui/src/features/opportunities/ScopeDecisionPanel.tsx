import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiProblem } from '../../api/http'
import { canBeIncluded, decideScope, includedByReviewer, type CandidateMatch, type ScopeDecision } from './candidateMatchApi'

/**
 * 可信机会池的入口。
 *
 * <p>自动准入只能判断证据齐不齐；"这是不是我要找的那类岗位"要人来定。纳入按钮只在证据齐全、
 * 用工身份已知时可点，并说明为什么不能点——不让人以为是按钮坏了。
 */
export function ScopeDecisionPanel({ match }: { match: CandidateMatch }) {
  const client = useQueryClient()
  const [note, setNote] = useState('')
  const decision = useMutation({
    mutationFn: (value: ScopeDecision) => decideScope(match.jobId, value, match.jobContentFingerprint, note),
    onSuccess: () => {
      // 可信池、初筛队列、今日概览都读准入状态，一起刷新。
      void client.invalidateQueries({ queryKey: ['candidate-matches'] })
      void client.invalidateQueries({ queryKey: ['decisions'] })
      void client.invalidateQueries({ queryKey: ['workbench'] })
      void client.invalidateQueries({ queryKey: ['job-library-summary'] })
    },
  })
  const included = includedByReviewer(match) || (decision.data?.admitted ?? false)
  const includable = canBeIncluded(match)
  const problem = decision.error instanceof ApiProblem ? decision.error : null

  if (included) {
    return <section className="scope-decision admitted" aria-label="可信机会池状态">
      <strong>已纳入可信机会池</strong>
      <span>它会参与资格判断、T1/T2/T3 分层和 Agent 查询。岗位内容再变化时会自动撤销，需要重新确认。</span>
    </section>
  }
  if (decision.data && decision.data.decision === 'EXCLUDE') {
    return <section className="scope-decision excluded" aria-label="可信机会池状态">
      <strong>已标记为不属于目标范围</strong>
      <span>它不会再出现在初筛队列里；原始记录仍保留在岗位库。</span>
    </section>
  }

  return <section className="scope-decision" aria-label="确认是否纳入可信机会池">
    <p className="official-detail-kicker">纳入可信机会池</p>
    <p>{includable
      ? '关键字段都有官网证据，用工身份已知。确认它属于你要找的稳定技术岗后，它才会进入可信机会池。'
      : !match.employmentIdentityConfirmed
        ? '用工身份尚未由官网证据确认，暂时不能纳入。可以先标记为不属于目标范围。'
        : '关键字段的官网证据还不齐全（见上方缺失项），暂时不能纳入；人工确认不能替证据作保。'}</p>
    <label htmlFor={`scope-note-${match.jobId}`}>备注（可选）</label>
    <textarea id={`scope-note-${match.jobId}`} rows={2} maxLength={500} value={note}
      onChange={event => setNote(event.target.value)} placeholder="例如：信息中心岗位，职责与系统建设直接相关" />
    <div className="scope-decision-actions">
      <button type="button" disabled={!includable || decision.isPending}
        onClick={() => decision.mutate('INCLUDE')}>纳入可信机会池</button>
      <button type="button" className="secondary" disabled={decision.isPending}
        onClick={() => decision.mutate('EXCLUDE')}>不属于目标范围</button>
    </div>
    {problem && <p className="agent-error" role="alert">{problem.status === 403
      ? '只有复核人账号可以决定岗位是否进入可信机会池。'
      : problem.message}</p>}
  </section>
}
