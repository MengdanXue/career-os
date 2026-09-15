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

/** 程序渲染的岗位结果。字段全部来自确定性服务，模型改不了其中任何一个。 */
export type AgentRunJob = {
  jobPostingId: string
  jobTitle: string | null
  organizationName: string | null
  eligibilityStatus: string | null
  tier: string | null
  applicationEndsOn: string | null
  restrictions: string[]
}

export type AgentRunResponse = {
  outcome: AgentRunOutcome
  /** 通过校验时才有值；被拒时为空，页面回落到确定性事实块。 */
  narrative: string | null
  /** 追问通过校验时才有值。 */
  question: string | null
  violations: string[]
  /** 这段话点名依据的观察序号。空表示没点名，或点名的依据不成立。 */
  groundedOn: number[]
  /** 消耗的预算单位：工具调用次数 + 内部逐岗评估次数。 */
  budgetSpent: number
  budgetLimit: number
  sessionId: string | null
  profileVersion: string | null
  jobs: AgentRunJob[]
  pendingConfirmations: PendingConfirmation[]
  tools: ToolView[]
  trace: TraceStep[]
  observations: ObservationView[]
}

/** 刷新之后取回的上一轮。带 answered，好区分"还没答"和"答过了"。 */
export type AgentSessionView = {
  sessionId: string
  profileVersion: string
  /** 资料已变，这份列表与这些问题不再对应当前结论。 */
  stale: boolean
  /** 上一轮追问时用户原本要办的那件事；刷新之后据此说明他在回答什么。 */
  openTask: string | null
  /** 系统当时问出去的那句话。 */
  pendingQuestion: string | null
  jobIdsInOrder: string[]
  pendingConfirmations: (PendingConfirmation & { answered: boolean })[]
}

export function runAgent(candidateId: string, question: string, sessionId?: string) {
  return requestJson<AgentRunResponse>(`/api/v1/candidates/${candidateId}/agent-runs`, {
    method: 'POST',
    body: JSON.stringify({ question, sessionId: sessionId ?? null }),
  })
}

/**
 * 取回上一轮会话。
 *
 * <p>走的是查询接口自己的会话读取，不另立一个：另立一个就会少掉 answered，
 * 刷新之后已经答过的问题会被再问一遍，用户分不出"还没答"和"答过了"。
 */
export function fetchAgentSession(candidateId: string, sessionId: string) {
  return requestJson<AgentSessionView>(`/api/v1/candidates/${candidateId}/agent-queries/${sessionId}`)
}
