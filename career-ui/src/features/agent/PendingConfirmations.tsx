import { useMutation } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { ApiProblem } from '../../api/http'
import type { PendingConfirmation } from './agentApi'
import {
  answerOptions, factLabels, recordConfirmation, statusLabels,
  type ConfirmationRequest, type ConfirmationResponse, type DecisionChangeSummary,
} from './confirmationApi'

type Attempt = {
  item: PendingConfirmation
  request: ConfirmationRequest
  asOf: string
  response?: ConfirmationResponse
}
type Attempts = Record<string, Attempt>
const completed = new Set(['RECORDED', 'ALREADY_RECORDED', 'NO_CHANGE_NEEDED'])
export function confirmationAttemptsKey(candidateId: string, sessionId: string) {
  return `career-os.confirmation-attempts.v1:${candidateId}:${sessionId}`
}

function readAttempts(key: string, sessionId: string): Attempts {
  try {
    const values: unknown = JSON.parse(sessionStorage.getItem(key) ?? '{}')
    if (!isRecord(values)) return {}
    const valid: Attempts = {}
    for (const [fact, attempt] of Object.entries(values)) {
      if (!Object.hasOwn(answerOptions, fact) || !isRecord(attempt) || !isRecord(attempt.request)) continue
      const request = attempt.request
      if (request.factKey !== fact || request.sessionId !== sessionId
        || typeof request.idempotencyKey !== 'string' || !request.idempotencyKey.trim()
        || typeof request.acknowledgedChange !== 'boolean' || typeof attempt.asOf !== 'string'
        || !/^\d{4}-\d{2}-\d{2}$/.test(attempt.asOf)
        || !answerOptions[fact].some(option => option.value === request.value)) continue
      // A damaged display receipt must not destroy an intact request/key. Recover that
      // request explicitly instead of treating malformed browser data as a new action.
      const item = isRecord(attempt.item) && attempt.item.factKey === fact
        && typeof attempt.item.question === 'string' && typeof attempt.item.jobPostingId === 'string'
        ? attempt.item as PendingConfirmation
        : { factKey: fact, question: `${factLabels[fact]}的原请求需要恢复确认。`, jobPostingId: '' }
      valid[fact] = { item, asOf: attempt.asOf, request: request as ConfirmationRequest,
        ...(validResponse(attempt.response) ? { response: attempt.response } : {}) }
    }
    return valid
  } catch { return {} }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function validResponse(value: unknown): value is ConfirmationResponse {
  if (!isRecord(value) || typeof value.result !== 'string'
    || !['RECORDED', 'RECORDED_RECOMPUTE_DEFERRED', 'ALREADY_RECORDED', 'IDEMPOTENCY_KEY_REUSED',
      'NO_CHANGE_NEEDED', 'CHANGE_REQUIRES_ACKNOWLEDGEMENT', 'PROFILE_VERSION_CHANGED', 'REQUIRES_DOCUMENT'].includes(value.result)
    || value.evidenceStrength !== 'SELF_REPORTED' || typeof value.message !== 'string'
    || typeof value.profileVersionBefore !== 'string' || typeof value.profileVersionAfter !== 'string') return false
  if (value.pendingChange != null && (!isRecord(value.pendingChange)
    || typeof value.pendingChange.factKey !== 'string' || typeof value.pendingChange.from !== 'string'
    || typeof value.pendingChange.to !== 'string')) return false
  if (value.changes == null) return true
  const changes = value.changes
  if (!isRecord(changes) || typeof changes.available !== 'boolean'
    || (changes.message != null && typeof changes.message !== 'string') || !Array.isArray(changes.affectedJobs)) return false
  if (!changes.available) return true
  return ['newlyEligibleCount', 'resolvedUncertaintyCount', 'newlyIneligibleCount'].every(key =>
    typeof changes[key] === 'number' && Number.isInteger(changes[key]) && changes[key] >= 0)
    && changes.affectedJobs.every(job => isRecord(job) && typeof job.jobId === 'string'
      && typeof job.title === 'string' && typeof job.organizationName === 'string'
      && typeof job.previousStatus === 'string' && Object.hasOwn(statusLabels, job.previousStatus)
      && typeof job.currentStatus === 'string' && Object.hasOwn(statusLabels, job.currentStatus)
      && Array.isArray(job.reasons) && job.reasons.every(reason => typeof reason === 'string'))
}

/** 一次明确选择生成一把新钥匙；网络重试、覆盖确认及补重算只重放该次选择的请求。 */
export function PendingConfirmations({ candidateId, sessionId, items, disabled = false, onConfirmed }: {
  candidateId: string
  sessionId: string
  items: PendingConfirmation[]
  disabled?: boolean
  onConfirmed?: () => Promise<void>
}) {
  const storageKey = confirmationAttemptsKey(candidateId, sessionId)
  const [attempts, setAttempts] = useState<Attempts>(() => readAttempts(storageKey, sessionId))
  const current = useRef(attempts)
  const [editing, setEditing] = useState<Record<string, boolean>>({})
  const [uncertain, setUncertain] = useState<Record<string, boolean>>({})
  const [persistenceError, setPersistenceError] = useState<string | null>(null)
  const [refreshError, setRefreshError] = useState<string | null>(null)

  function save(next: Attempts, receipt = false) {
    if (receipt) { current.current = next; setAttempts(next) }
    try { sessionStorage.setItem(storageKey, JSON.stringify(next)); setPersistenceError(null) }
    catch {
      setPersistenceError(receipt
        ? '已收到业务回执，但浏览器未能保存恢复信息。本页保留回执；刷新后请用已保存的原请求恢复，不要当作未发送。'
        : '无法保存这次操作的恢复信息，尚未发送新的回答。请允许浏览器会话存储后重试。')
      return false
    }
    current.current = next
    setAttempts(next)
    return true
  }

  const submit = useMutation({
    mutationFn: (attempt: Attempt) => recordConfirmation(candidateId, attempt.asOf, attempt.request),
    onSuccess: async (response, attempt) => {
      // 即使剩余问题被服务端移除，回执和原请求仍保留，重算恢复入口不会跟着消失。
      save({ ...current.current, [attempt.request.factKey]: { ...attempt, response } }, true)
      if (completed.has(response.result) || response.result === 'RECORDED_RECOMPUTE_DEFERRED'
        || response.result === 'PROFILE_VERSION_CHANGED') {
        try { await onConfirmed?.(); setRefreshError(null) }
        catch { setRefreshError('回答回执已收到，但剩余问题尚未刷新；请先刷新会话与问题，再继续确认。') }
      }
    },
  })

  function begin(item: PendingConfirmation, value: string) {
    const attempt: Attempt = {
      item,
      asOf: new Date().toISOString().slice(0, 10),
      request: { factKey: item.factKey, value, sessionId, idempotencyKey: crypto.randomUUID(), acknowledgedChange: false },
    }
    if (!save({ ...current.current, [item.factKey]: attempt })) return
    setEditing(previous => ({ ...previous, [item.factKey]: false }))
    setUncertain(previous => ({ ...previous, [item.factKey]: false }))
    submit.mutate(attempt)
  }

  function retry(attempt: Attempt, acknowledged = attempt.request.acknowledgedChange) {
    const next: Attempt = { item: attempt.item, asOf: attempt.asOf,
      request: { ...attempt.request, acknowledgedChange: acknowledged } }
    if (save({ ...current.current, [attempt.request.factKey]: next })) submit.mutate(next)
  }

  const hasUnsettled = Object.values(attempts).some(attempt => !attempt.response
    || attempt.response.result === 'RECORDED_RECOMPUTE_DEFERRED')
  const blocked = disabled || !!refreshError
  const byFact = new Map(items.map(item => [item.factKey, item]))
  for (const attempt of Object.values(attempts)) if (!byFact.has(attempt.item.factKey)) byFact.set(attempt.item.factKey, attempt.item)
  if (byFact.size === 0) return null
  const error = submit.error instanceof ApiProblem ? submit.error.message : submit.error ? '请求结果尚未确认，请重试原请求。' : null

  return <section className="pending-confirmations" aria-label="待确认事项">
    <h3>{items.length > 0 ? '这些还要你确认' : '本会话确认回执'}</h3>
    <p className="confirmation-basis">你的回答按本人声明记录，系统不会把它当作官方核实结果。</p>
    {hasUnsettled && <p role="status">上一次操作尚未完成，请先用原请求恢复，再开始新的回答。</p>}
    {[...byFact.values()].map(item => {
      const attempt = attempts[item.factKey]
      const outcome = attempt?.response
      const showOptions = editing[item.factKey] || (!attempt || (outcome && !completed.has(outcome.result)
        && outcome.result !== 'RECORDED_RECOMPUTE_DEFERRED' && outcome.result !== 'CHANGE_REQUIRES_ACKNOWLEDGEMENT'))
      return <article key={item.factKey} data-fact-key={item.factKey} data-job-id={item.jobPostingId}
        data-result={outcome?.result ?? (attempt ? 'REQUEST_UNCONFIRMED' : 'PENDING')}>
        <p className="confirmation-question"><strong>{factLabels[item.factKey] ?? item.factKey}</strong><span>{item.question}</span></p>
        {showOptions && <div className="confirmation-options">
          {(answerOptions[item.factKey] ?? []).map(option => <button key={option.value} type="button"
            disabled={blocked || submit.isPending || hasUnsettled}
            onClick={() => begin(item, option.value)}>{option.label}</button>)}
          <button type="button" disabled={blocked || submit.isPending || hasUnsettled}
            onClick={() => setUncertain(previous => ({ ...previous, [item.factKey]: true }))}>仍不确定，暂不回答</button>
          {uncertain[item.factKey] && <p>保持待确认，没有保存为已满足，也没有写入声明。</p>}
        </div>}
        {attempt && !outcome && !submit.isPending && <div>
          <p>上次请求的业务结果尚未确认，不能当作失败后重新写入。</p>
          <button type="button" disabled={disabled} onClick={() => retry(attempt)}>重试原请求</button>
        </div>}
        {outcome && <div className="confirmation-outcome">
          <p>{outcome.message}</p>
          <p className="confirmation-version">回执资料版本：<code>{outcome.profileVersionBefore}</code> → <code>{outcome.profileVersionAfter}</code></p>
          {outcome.result === 'CHANGE_REQUIRES_ACKNOWLEDGEMENT' && outcome.pendingChange && <div className="confirmation-change" role="group" aria-label="确认修改">
            <p>{outcome.pendingChange.from} → {outcome.pendingChange.to}</p>
            <button type="button" disabled={disabled || submit.isPending} onClick={() => retry(attempt, true)}>确认修改</button>
            <button type="button" disabled={disabled || submit.isPending} onClick={() => setEditing(previous => ({ ...previous, [item.factKey]: true }))}>重新选择答案</button>
          </div>}
          {outcome.result === 'RECORDED_RECOMPUTE_DEFERRED' && <div className="confirmation-deferred">
            <p>岗位结论还没刷新。点击恢复将只补重算，沿用原请求，不重复记录回答。</p>
            <button type="button" disabled={disabled || submit.isPending} onClick={() => retry(attempt)}>恢复重算</button>
          </div>}
          {outcome.changes && <Changes changes={outcome.changes} />}
          {completed.has(outcome.result) && !editing[item.factKey] && <button type="button"
            disabled={blocked || submit.isPending || hasUnsettled}
            onClick={() => setEditing(previous => ({ ...previous, [item.factKey]: true }))}>更改{factLabels[item.factKey] ?? item.factKey}回答</button>}
        </div>}
      </article>
    })}
    {error && <p className="agent-error" role="alert">{error}</p>}
    {persistenceError && <p role="alert">{persistenceError}</p>}
    {refreshError && <div><p role="alert">{refreshError}</p><button type="button" disabled={submit.isPending}
      onClick={async () => { try { await onConfirmed?.(); setRefreshError(null) } catch { /* 保持未刷新状态。 */ } }}>重试刷新剩余问题</button></div>}
  </section>
}

function Changes({ changes }: { changes: DecisionChangeSummary }) {
  if (!changes.available) return <p className="confirmation-unavailable">{changes.message ?? '这次变化暂时算不出来。'}</p>
  return <div className="confirmation-changes">
    <p className="confirmation-counts">重算后：新增可报 {changes.newlyEligibleCount} 个，待确认已解决 {changes.resolvedUncertaintyCount} 个，转为不可报 {changes.newlyIneligibleCount} 个。</p>
    {changes.affectedJobs.length > 0 && <ul>{changes.affectedJobs.map(job => <li key={job.jobId} data-job-id={job.jobId}>
      <strong>{job.title}</strong><small>{job.organizationName}</small>
      <span className="confirmation-transition">{statusLabels[job.previousStatus]} → {statusLabels[job.currentStatus]}</span>
      {job.reasons.length > 0 && <ul className="confirmation-reasons">{job.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul>}
    </li>)}</ul>}
  </div>
}
