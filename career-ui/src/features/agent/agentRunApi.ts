import { requestJson } from '../../api/http'
import type { PendingConfirmation } from './agentApi'

export type ToolParameter = {
  name: string
  required: boolean
  description: string
  allowedValues: string[]
}

export type ToolView = {
  name: string
  description: string
  parameters: ToolParameter[]
}

export type TraceStep = {
  tool: string
  arguments: Record<string, string>
  why: string | null
  accepted: boolean
  reason: string
  /** 这一步真实花掉的预算单位，含工具内部的逐岗评估。被拒的步骤是 0。 */
  budgetUnits: number
}

export type ObservationView = {
  tool: string
  ok: boolean
  summary: string
  data: Record<string, unknown>
}

export type AgentRunOutcome =
  | 'FINISHED' | 'ASKED_USER' | 'BUDGET_EXHAUSTED' | 'STEP_LIMIT_REACHED' | 'PLANNER_FAILED'

export type AgentRunResponse = {
  outcome: AgentRunOutcome
  /** 通过校验时才有值；被拒时为空，页面回落到确定性事实块。 */
  narrative: string | null
  /** 追问通过校验时才有值。 */
  question: string | null
  violations: string[]
  /** 消耗的预算单位：工具调用次数 + 内部逐岗评估次数。 */
  budgetSpent: number
  budgetLimit: number
  /** 这次回答背后有几条成功的工具结果。零表示没有依据。 */
  groundedIn: number
  sessionId: string | null
  profileVersion: string | null
  tools: ToolView[]
  trace: TraceStep[]
  observations: ObservationView[]
}

export type AgentSessionView = {
  sessionId: string
  profileVersion: string
  /** 资料已变，这份列表与这些问题不再对应当前结论。 */
  stale: boolean
  lastJobIdsInOrder: string[]
  pendingConfirmations: PendingConfirmation[]
  updatedAt: string
}

export function runAgent(candidateId: string, question: string, sessionId?: string) {
  return requestJson<AgentRunResponse>(`/api/v1/candidates/${candidateId}/agent-runs`, {
    method: 'POST',
    body: JSON.stringify({ question, sessionId: sessionId ?? null }),
  })
}

export function fetchAgentSession(candidateId: string, sessionId: string) {
  return requestJson<AgentSessionView>(`/api/v1/candidates/${candidateId}/agent-sessions/${sessionId}`)
}
