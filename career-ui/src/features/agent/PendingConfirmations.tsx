import { useMutation } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { ApiProblem } from '../../api/http'
import type { PendingConfirmation } from './agentApi'
import {
  answerOptions, factLabels, recordConfirmation, statusLabels,
  type ConfirmationResponse, type DecisionChangeSummary,
} from './confirmationApi'

/**
 * 待确认事项：说清为什么问，收下回答，然后展示真的算出来的变化。
 *
 * <p>三处措辞是刻意的，不是文案偏好：
 *
 * <p>回答一律标成本人声明。系统没有见过任何材料，写成"已核实"会让后面每一个结论
 * 都建立在一句话上，而用户以为那是查证过的。
 *
 * <p>回答之前不说"确认后会解锁几个岗位"。会不会变、变成什么，只有真的重算过才知道；
 * 先给一个数字，等于先给一个承诺。
 *
 * <p>算不出变化时说"算不出来"，不显示零。三个计数都是 null 时显示成 0，
 * 会读成"确认了也没用"，那是另一回事。
 */
export function PendingConfirmations({ candidateId, sessionId, items }: {
  candidateId: string
  sessionId: string
  items: PendingConfirmation[]
}) {
  const asOf = useMemo(() => new Date().toISOString().slice(0, 10), [])
  // 幂等钥匙按"这一轮会话的这个字段"固定：重试不会写第二次，也不会再推高一次资料版本。
  const [answered, setAnswered] = useState<Record<string, ConfirmationResponse>>({})
  const [pendingValue, setPendingValue] = useState<Record<string, string>>({})

  const submit = useMutation({
    mutationFn: (input: { factKey: string; value: string; acknowledged: boolean }) =>
      recordConfirmation(candidateId, asOf, {
        factKey: input.factKey,
        value: input.value,
        sessionId,
        idempotencyKey: `${sessionId}:${input.factKey}`,
        acknowledgedChange: input.acknowledged,
      }),
    onSuccess: (response, input) => {
      setAnswered(current => ({ ...current, [input.factKey]: response }))
      setPendingValue(current => ({ ...current, [input.factKey]: input.value }))
    },
  })

  if (items.length === 0) return null
  const error = submit.error instanceof ApiProblem ? submit.error.message : null

  return <section className="pending-confirmations" aria-label="待确认事项">
    <h3>这些还要你确认</h3>
    <p className="confirmation-basis">你的回答按本人声明记录，系统不会把它当作官方核实结果。</p>
    {items.map(item => {
      const outcome = answered[item.factKey]
      return <article key={item.factKey}>
        <p className="confirmation-question">
          <strong>{factLabels[item.factKey] ?? item.factKey}</strong>
          <span>{item.question}</span>
        </p>
        {!outcome && <div className="confirmation-options">
          {(answerOptions[item.factKey] ?? []).map(option => <button
            key={option.value}
            type="button"
            disabled={submit.isPending}
            onClick={() => submit.mutate({ factKey: item.factKey, value: option.value, acknowledged: false })}
          >{option.label}</button>)}
        </div>}
        {outcome && <Outcome
          outcome={outcome}
          onAcknowledge={() => submit.mutate({
            factKey: item.factKey,
            value: pendingValue[item.factKey],
            acknowledged: true,
          })}
          acknowledging={submit.isPending}
        />}
      </article>
    })}
    {error && <p className="agent-error" role="alert">{error}</p>}
  </section>
}

function Outcome({ outcome, onAcknowledge, acknowledging }: {
  outcome: ConfirmationResponse
  onAcknowledge: () => void
  acknowledging: boolean
}) {
  if (outcome.result === 'CHANGE_REQUIRES_ACKNOWLEDGEMENT' && outcome.pendingChange) {
    return <div className="confirmation-change" role="group" aria-label="确认修改">
      <p>{outcome.message}</p>
      <p className="confirmation-from-to">
        {outcome.pendingChange.from} → {outcome.pendingChange.to}
      </p>
      <button type="button" onClick={onAcknowledge} disabled={acknowledging}>确认修改</button>
    </div>
  }
  return <div className="confirmation-outcome">
    <p>{outcome.message}</p>
    {outcome.result === 'RECORDED_RECOMPUTE_DEFERRED' &&
      <p className="confirmation-deferred">岗位结论还没刷新，稍后重试即可，回答不会重复记录。</p>}
    {outcome.changes && <Changes changes={outcome.changes} />}
  </div>
}

function Changes({ changes }: { changes: DecisionChangeSummary }) {
  if (!changes.available) {
    // 没有对照基线就说算不出来，不显示三个 0。
    return <p className="confirmation-unavailable">{changes.message ?? '这次变化暂时算不出来。'}</p>
  }
  return <div className="confirmation-changes">
    <p className="confirmation-counts">
      重算后：新增可报 {changes.newlyEligibleCount} 个，待确认已解决 {changes.resolvedUncertaintyCount} 个，
      转为不可报 {changes.newlyIneligibleCount} 个。
    </p>
    {changes.affectedJobs.length > 0 && <ul>
      {changes.affectedJobs.map(job => <li key={job.jobId}>
        <strong>{job.title}</strong>
        <small>{job.organizationName}</small>
        <span className="confirmation-transition">
          {statusLabels[job.previousStatus]} → {statusLabels[job.currentStatus]}
        </span>
        {job.reasons.length > 0 && <ul className="confirmation-reasons">
          {job.reasons.map(reason => <li key={reason}>{reason}</li>)}
        </ul>}
      </li>)}
    </ul>}
  </div>
}
