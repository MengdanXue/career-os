import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { ApiProblem } from '../../api/http'
import { AgentAnswer } from './AgentAnswer'
import { askCareerOs, getAgentSession } from './agentApi'
import { readAgentSnapshot, saveAgentSnapshot, type AgentSnapshot } from './agentSessionStorage'

const examples = ['本周最值得准备什么？', '为什么把这个岗位放在 T1？', '哪些岗位还缺关键证据？']

export function AgentComposer({ expanded = false }: { expanded?: boolean }) {
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  return <CandidateComposer key={candidateId} candidateId={candidateId} expanded={expanded} />
}

function CandidateComposer({ candidateId, expanded }: { candidateId: string; expanded: boolean }) {
  const client = useQueryClient()
  const [snapshot, setSnapshot] = useState(() => ({ result: readAgentSnapshot(candidateId), revision: 0, captureCanonical: false }))
  const { result, revision, captureCanonical } = snapshot
  const [open, setOpen] = useState(expanded || !!result)
  const [question, setQuestion] = useState('')
  const [storageError, setStorageError] = useState<string | null>(null)
  const sessionId = result?.sessionId
  const session = useQuery({
    // A new answer must bind to a new GET, never a cached/in-flight GET for an older answer.
    queryKey: ['agent-session', candidateId, sessionId, revision],
    queryFn: () => getAgentSession(candidateId, sessionId!),
    enabled: !!sessionId,
    retry: false,
    staleTime: 0,
  })

  function persist(next: AgentSnapshot | null) {
    try { saveAgentSnapshot(candidateId, next); setStorageError(null) }
    catch { setStorageError('浏览器未能保存会话引用；本页可继续，但刷新恢复尚未保存成功。') }
  }

  function remember(next: AgentSnapshot | null) {
    setSnapshot(previous => ({ result: next, revision: previous.revision + 1, captureCanonical: !!next?.sessionId }))
    persist(next)
  }

  const query = useMutation({
    mutationFn: (value: string) => askCareerOs(candidateId, value, sessionId),
    onSuccess: next => remember(next),
  })

  const matchingSession = !!result && !!session.data && session.data.sessionId === sessionId
    && Array.isArray(session.data.jobIdsInOrder) && Array.isArray(session.data.pendingConfirmations)
    && result.decisions.every(decision => session.data.jobIdsInOrder.includes(decision.jobId))
  const matchesDisplayedOrder = matchingSession && (result!.decisions.length <= 1
    || sameOrder(result!.decisions.map(decision => decision.jobId), session.data!.jobIdsInOrder))
  useEffect(() => {
    if (!captureCanonical || session.isFetching || !session.isSuccess || !matchesDisplayedOrder) return
    const bound = { ...result!, canonicalJobIdsInOrder: [...session.data!.jobIdsInOrder] }
    setSnapshot(previous => previous.revision === revision && previous.captureCanonical
      ? { ...previous, result: bound, captureCanonical: false } : previous)
    persist(bound)
  }, [captureCanonical, session.isFetching, session.isSuccess, matchesDisplayedOrder, result, session.data, revision])

  const validReference = matchesDisplayedOrder && !captureCanonical && !!result?.canonicalJobIdsInOrder
    && sameOrder(result.canonicalJobIdsInOrder, session.data!.jobIdsInOrder)
  const ready = validReference && !session.isFetching && !session.isError
  const snapshotIsOld = !!result && !!session.data && result.profileVersion !== session.data.currentProfileVersion
  const scopedPending = ready ? session.data!.pendingConfirmations.filter(item =>
    !item.answered && result!.decisions.some(decision => decision.jobId === item.jobPostingId)) : []

  async function refreshSession() {
    // 写入成功不代表问题刷新成功。失败时保持错误并锁定下一项，不自动重放任何业务操作。
    const refreshed = await session.refetch()
    await client.invalidateQueries({ queryKey: ['watchlist', candidateId] })
    if (refreshed.error) throw refreshed.error
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = question.trim()
    if (value) query.mutate(value)
  }

  const error = query.error instanceof ApiProblem ? query.error.message : query.error ? '这次分析没有完成，请重试。' : null
  return <aside className="agent-composer" data-open={open} aria-label="Career OS 决策助手">
    {!open ? <button className="agent-launcher" type="button" aria-label="打开 Career OS 决策助手" onClick={() => setOpen(true)}><span aria-hidden="true">C</span><strong>问 Career OS</strong><small>基于岗位与证据回答</small></button> : <div className="agent-panel">
      <header><div><p className="eyebrow">DECISION ASSISTANT</p><strong>问 Career OS</strong><small>只基于已采集岗位、你的资料和可引用证据；动态工具选择未开启</small></div><button type="button" aria-label="收起 Career OS" onClick={() => setOpen(false)}>×</button></header>
      {!result && <div className="agent-examples" aria-label="问题示例">{examples.map(example => <button type="button" key={example} onClick={() => setQuestion(example)}>{example}</button>)}</div>}
      {sessionId && <section className="agent-session-state" aria-label="会话恢复状态" data-session-id={sessionId}>
        <p>原会话：<code>{sessionId}</code></p>
        {session.isFetching && <p role="status">正在读取原会话、岗位顺序与剩余问题…</p>}
        {session.isError && <p role="alert">原会话读取失败，尚未恢复问题；不会用新排名替代原岗位。{session.error instanceof ApiProblem ? session.error.message : ''}</p>}
        {session.data && !session.isFetching && !validReference && <p role="alert">这份本地快照缺少已核对的岗位顺序，或与原会话的岗位记录不一致，未恢复展示。请明确重新查询列表。</p>}
        {ready && <>
          <p>问题所依据的资料版本：<code data-testid="session-profile-version">{session.data!.profileVersion}</code></p>
          <p>当前资料版本：<code>{session.data!.currentProfileVersion}</code></p>
          <p>剩余适用问题：<span data-testid="remaining-confirmations">{scopedPending.length}</span></p>
          {session.data!.stale && <p role="alert">资料已在其他操作中变化，旧问题与序号已过期。请明确重新查询列表后再回答。</p>}
        </>}
        <button type="button" disabled={session.isFetching} onClick={() => void session.refetch()}>刷新会话与问题</button>
        {session.isError && <button type="button" onClick={() => remember(null)}>开始新会话</button>}
      </section>}
      {result && (!sessionId || validReference) && <>
        {snapshotIsOld && <p className="answer-snapshot-note" role="status">下方是上次展示的结论快照，资料版本已变化；最新重算结果见确认回执，未把旧快照冒充当前结论。</p>}
        <AgentAnswer result={result} candidateId={candidateId} pendingItems={scopedPending}
          confirmationsDisabled={!ready || !!session.data?.stale || query.isPending}
          onConfirmed={refreshSession} />
      </>}
      {storageError && <p role="alert">{storageError}</p>}
      {error && <p className="agent-error" role="alert">{error}</p>}
      <form onSubmit={submit}><label htmlFor="career-agent-question">向 Career OS 提问</label><div><textarea id="career-agent-question" rows={2} value={question} onChange={event => setQuestion(event.target.value)} placeholder="例如：本周最值得准备什么？" /><button type="submit" disabled={!question.trim() || query.isPending || session.isFetching}>{query.isPending ? '分析中…' : '分析'}</button></div></form>
    </div>}
  </aside>
}

function sameOrder(left: string[], right: string[]) {
  return left.length === right.length && left.every((id, index) => id === right[index])
}
