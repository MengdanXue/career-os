import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { ApiProblem } from '../../api/http'
import { AgentAnswer } from './AgentAnswer'
import { AgentRunPanel } from './AgentRunPanel'
import { askCareerOs } from './agentApi'
import { fetchAgentSession } from './agentRunApi'
import { factLabels } from './confirmationApi'
import { PendingConfirmations } from './PendingConfirmations'

const examples = ['本周最值得准备什么？', '为什么把这个岗位放在 T1？', '哪些岗位还缺关键证据？']

/** 会话 ID 存这里，刷新之后还能接着走。 */
const SESSION_STORAGE_KEY = 'career-os.agent-session'

function storedSession(): string | undefined {
  try {
    return localStorage.getItem(SESSION_STORAGE_KEY) ?? undefined
  } catch {
    // 隐私模式下读写 localStorage 会抛。会话续跑是锦上添花，不能因此让整个面板打不开。
    return undefined
  }
}

function rememberSession(sessionId: string) {
  try {
    localStorage.setItem(SESSION_STORAGE_KEY, sessionId)
  } catch {
    // 同上：存不下就只是这一次刷新恢复不了，不影响当前这一轮。
  }
}

export function AgentComposer({ expanded = false }: { expanded?: boolean }) {
  const [open, setOpen] = useState(expanded)
  const [question, setQuestion] = useState('')
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  // 会话 ID 要跨轮次带着走。不带的话每一句都是新的一轮，"第二个怎么样"就没有那份列表可指。
  // 也要跨刷新带着走：只放在内存里的话，用户按一次 F5，待确认事项和它们依据的资料版本一起消失，
  // 回答到一半刷新最糟——已经写进去的那条没了着落，他不知道自己答过没有。
  const [sessionId, setSessionId] = useState<string | undefined>(storedSession)
  // 只恢复"打开页面这一刻存着的那一轮"。跟着 sessionId 走的话，本轮自己刚拿到的会话
  // 会立刻被再取一次——那不是刷新恢复，只是多打一次接口。
  const [restoreTarget] = useState<string | undefined>(storedSession)
  const query = useMutation({
    mutationFn: (value: string) => askCareerOs(candidateId, value, sessionId),
    onSuccess: result => {
      if (result.sessionId) {
        setSessionId(result.sessionId)
        rememberSession(result.sessionId)
      }
    },
  })

  // 刷新后把上一轮取回来。取不到（过期、不是本人的）就当新开一轮，不报错打扰用户。
  const restored = useQuery({
    queryKey: ['agent-session', candidateId, restoreTarget],
    queryFn: () => fetchAgentSession(candidateId, restoreTarget!),
    enabled: Boolean(restoreTarget),
    retry: false,
  })

  useEffect(() => {
    if (restored.isError) setSessionId(undefined)
  }, [restored.isError])

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = question.trim()
    if (value) query.mutate(value)
  }

  const error = query.error instanceof ApiProblem ? query.error.message : query.error ? '这次分析没有完成，请重试。' : null
  const recovered = !query.data && restored.data ? restored.data : null
  return <aside className="agent-composer" data-open={open} aria-label="Career OS 决策助手">
    {!open ? <button className="agent-launcher" type="button" aria-label="打开 Career OS 决策助手" onClick={() => setOpen(true)}><span aria-hidden="true">C</span><strong>问 Career OS</strong><small>基于岗位与证据回答</small></button> : <div className="agent-panel">
      <header><div><p className="eyebrow">DECISION AGENT</p><strong>问 Career OS</strong><small>只基于已采集岗位、你的资料和可引用证据</small></div><button type="button" aria-label="收起 Career OS" onClick={() => setOpen(false)}>×</button></header>
      {!query.data && !recovered && <div className="agent-examples" aria-label="问题示例">{examples.map(example => <button type="button" key={example} onClick={() => setQuestion(example)}>{example}</button>)}</div>}
      {recovered && <section className="agent-restored" aria-label="上一轮会话">
        <p>接着上一轮。</p>
        {/* 资料在这期间变了就说出来，不让页面拿着旧序号继续问"第二个怎么样"：
            重新排出来的第二个可能是另一个岗位，而用户看不出它换了对象。 */}
        {recovered.stale && <p className="agent-restored-stale" role="status">
          你的资料已经更新，上一份列表的排序不再对应当前结论，请重新查询后再按序号提问。
        </p>}
        {/* 答过的标出来，不删掉：删掉就看不出系统问过这一条，用户会以为它凭空消失了；
            原样再问一遍则更糟——他分不出"还没答"和"答过了"。 */}
        {recovered.pendingConfirmations.some(item => item.answered) && <p className="agent-restored-answered">
          已答过：{recovered.pendingConfirmations.filter(item => item.answered)
            .map(item => factLabels[item.factKey] ?? item.factKey).join('、')}
        </p>}
        {recovered.pendingConfirmations.some(item => !item.answered) && <PendingConfirmations
          candidateId={candidateId} sessionId={recovered.sessionId}
          items={recovered.pendingConfirmations.filter(item => !item.answered)} />}
      </section>}
      {query.data && <AgentAnswer result={query.data} candidateId={candidateId} />}
      {error && <p className="agent-error" role="alert">{error}</p>}
      <form onSubmit={submit}><label htmlFor="career-agent-question">向 Career OS 提问</label><div><textarea id="career-agent-question" rows={2} value={question} onChange={event => setQuestion(event.target.value)} placeholder="例如：本周最值得准备什么？" /><button type="submit" disabled={!question.trim() || query.isPending}>{query.isPending ? '分析中…' : '分析'}</button></div></form>
      <AgentRunPanel candidateId={candidateId} sessionId={sessionId}
        onSession={next => { setSessionId(next); rememberSession(next) }} />
    </div>}
  </aside>
}
