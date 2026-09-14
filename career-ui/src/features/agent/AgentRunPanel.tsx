import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiProblem } from '../../api/http'
import { runAgent, type AgentRunResponse } from './agentRunApi'

/**
 * 只读工具编排的运行面板。
 *
 * <p>这一版模型只能查、读、解释和追问；改资料仍然只走用户自己点的那些确定性按钮，
 * 规划器够不着它们。所以这里把边界原样摆出来，而不是让用户相信它是只读的：
 *
 * <p><b>工具目录照抄。</b> 发给模型的是哪几个工具、每个收什么参数，页面上就显示哪几个。
 * 同一份定义，不另写一份文档——文档和代码对不上的时候，坏的是用户看到的东西。
 *
 * <p><b>被拒的步骤要显示。</b> 模型请求了一个没注册的工具、参数不合法、预算用完，
 * 这些都留在轨迹里。看不见的拦截等于没拦截。
 *
 * <p><b>没有模型就明说。</b> 后端返回 503 时这里写"模型未启用"，不退化成一个写死的流程冒充它。
 */
export function AgentRunPanel({ candidateId, sessionId }: { candidateId: string; sessionId?: string }) {
  const [question, setQuestion] = useState('')
  const [open, setOpen] = useState(false)
  const run = useMutation({ mutationFn: (value: string) => runAgent(candidateId, value, sessionId) })

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = question.trim()
    if (value) run.mutate(value)
  }

  const problem = run.error instanceof ApiProblem ? run.error : null
  return <section className="agent-run" aria-label="只读工具编排">
    <button type="button" className="agent-run-toggle" aria-expanded={open} onClick={() => setOpen(!open)}>
      {open ? '收起工具编排' : '让它自己选工具查（只读）'}
    </button>
    {open && <>
      <p className="agent-run-scope">
        这里模型只能查资料和追问。修改资料仍然要你自己点确认按钮，它做不到。
      </p>
      <form onSubmit={submit}>
        <label htmlFor="agent-run-question">交给它去查</label>
        <div>
          <textarea id="agent-run-question" rows={2} value={question}
            onChange={event => setQuestion(event.target.value)}
            placeholder="例如：我关注的那些有没有变化？" />
          <button type="submit" disabled={!question.trim() || run.isPending}>
            {run.isPending ? '运行中…' : '运行'}
          </button>
        </div>
      </form>
      {problem?.code === 'PLANNER_UNAVAILABLE' && <p className="agent-run-disabled" role="status">
        模型未启用，这个入口现在不可用。这里不会用一个写死的固定流程冒充它。
      </p>}
      {problem && problem.code !== 'PLANNER_UNAVAILABLE' &&
        <p className="agent-error" role="alert">{problem.message}</p>}
      {run.data && <RunResult result={run.data} />}
    </>}
  </section>
}

const outcomeLabels: Record<AgentRunResponse['outcome'], string> = {
  FINISHED: '它自己收敛了',
  ASKED_USER: '它转向追问你',
  BUDGET_EXHAUSTED: '共享预算用完了',
  STEP_LIMIT_REACHED: '步数到上限仍未收敛',
  PLANNER_FAILED: '这一步没有给出计划',
}

function RunResult({ result }: { result: AgentRunResponse }) {
  return <div className="agent-run-result" aria-live="polite">
    <p className="agent-run-outcome">{outcomeLabels[result.outcome]}</p>

    {result.narrative && <p className="answer-text">{result.narrative}</p>}
    {result.question && <p className="agent-run-question">{result.question}</p>}
    {result.violations.length > 0 && <div className="local-answer-note">
      {/* 被拒的原因要显示。只把话吞掉，用户看到的是一次"什么都没说"的运行。 */}
      <p>它这段话没有通过校验，已经整段丢弃</p>
      <ul className="answer-violations">{result.violations.map(reason => <li key={reason}>{reason}</li>)}</ul>
    </div>}

    <p className="agent-run-budget">
      本次消耗预算 {result.budgetSpent} / {result.budgetLimit} 个单位
      （一次工具调用算一个，工具内部每评估一个岗位再算一个）；
      背后有 {result.groundedIn} 条成功的工具结果。
    </p>

    <details className="agent-run-trace">
      <summary>它做了哪几步（含被拒的）</summary>
      <ol>
        {result.trace.map((step, index) => <li key={`${step.tool}-${index}`} data-accepted={step.accepted}>
          <strong>{step.tool}</strong>
          <span className="agent-run-step-reason">{step.accepted ? step.reason : `已拒绝：${step.reason}`}</span>
          {step.why && <small>它给的理由：{step.why}</small>}
          {Object.keys(step.arguments).length > 0 &&
            <code>{Object.entries(step.arguments).map(([key, value]) => `${key}=${value}`).join(' ')}</code>}
          <small>花费 {step.budgetUnits} 个预算单位</small>
        </li>)}
      </ol>
    </details>

    <details className="agent-run-observations">
      <summary>它看到了什么</summary>
      <ol>
        {result.observations.map((observation, index) =>
          <li key={`${observation.tool}-${index}`} data-ok={observation.ok}>
            <strong>{observation.tool}</strong>
            <span>{observation.ok ? observation.summary : `失败：${observation.summary}`}</span>
          </li>)}
      </ol>
    </details>

    <details className="agent-run-tools">
      <summary>这次它能用的工具（全部只读）</summary>
      <ul>
        {result.tools.map(tool => <li key={tool.name}>
          <strong>{tool.name}</strong>
          <span>{tool.description}</span>
          {tool.parameters.length > 0 && <ul>
            {tool.parameters.map(parameter => <li key={parameter.name}>
              <code>{parameter.name}</code>
              <span>{parameter.required ? '必填' : '可选'} · {parameter.description}</span>
              {parameter.allowedValues.length > 0 &&
                <small>只能取 {parameter.allowedValues.join('、')}</small>}
            </li>)}
          </ul>}
        </li>)}
      </ul>
    </details>
  </div>
}
