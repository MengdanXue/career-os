import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiProblem } from '../../api/http'
import { AgentAnswer } from './AgentAnswer'
import { askCareerOs } from './agentApi'

const examples = ['本周最值得准备什么？', '为什么把这个岗位放在 T1？', '哪些岗位还缺关键证据？']

export function AgentComposer({ expanded = false }: { expanded?: boolean }) {
  const [open, setOpen] = useState(expanded)
  const [question, setQuestion] = useState('')
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const query = useMutation({ mutationFn: (value: string) => askCareerOs(candidateId, value) })

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = question.trim()
    if (value) query.mutate(value)
  }

  const error = query.error instanceof ApiProblem ? query.error.message : query.error ? '这次分析没有完成，请重试。' : null
  return <aside className="agent-composer" data-open={open} aria-label="Career OS 决策助手">
    {!open ? <button className="agent-launcher" type="button" onClick={() => setOpen(true)}><span aria-hidden="true">C</span><strong>问 Career OS</strong><small>基于岗位与证据回答</small></button> : <div className="agent-panel">
      <header><div><p className="eyebrow">DECISION AGENT</p><strong>问 Career OS</strong><small>只基于已采集岗位、你的资料和可引用证据</small></div><button type="button" aria-label="收起 Career OS" onClick={() => setOpen(false)}>×</button></header>
      {!query.data && <div className="agent-examples" aria-label="问题示例">{examples.map(example => <button type="button" key={example} onClick={() => setQuestion(example)}>{example}</button>)}</div>}
      {query.data && <AgentAnswer result={query.data} />}
      {error && <p className="agent-error" role="alert">{error}</p>}
      <form onSubmit={submit}><label htmlFor="career-agent-question">向 Career OS 提问</label><div><textarea id="career-agent-question" rows={2} value={question} onChange={event => setQuestion(event.target.value)} placeholder="例如：本周最值得准备什么？" /><button type="submit" disabled={!question.trim() || query.isPending}>{query.isPending ? '分析中…' : '分析'}</button></div></form>
    </div>}
  </aside>
}
