import { requestJson } from '../../api/http'
import type { JobDecision } from '../opportunities/opportunityApi'

export type PendingConfirmation = {
  factKey: string
  question: string
  jobPostingId: string
  answered?: boolean
}

/** 服务端会话的只读投影；读取不重新排名、重算或推进资料版本。 */
export type AgentSessionResponse = {
  sessionId: string
  profileVersion: string
  currentProfileVersion: string
  stale: boolean
  jobIdsInOrder: string[]
  pendingConfirmations: PendingConfirmation[]
}

export function getAgentSession(candidateId: string, sessionId: string) {
  return requestJson<AgentSessionResponse>(`/api/v1/candidates/${candidateId}/agent-queries/${sessionId}`)
}

export type AgentResponse = {
  question: string
  answer: string
  decisions: JobDecision[]
  modelPhrased: boolean
  fallbackUsed: boolean
  disclaimer: string
  /** 模型叙述被拒的原因。为空表示没有拦截发生。 */
  violations?: string[]
  /** 这一轮的会话。带回给下一次提问，"第二个"才指向同一份列表。 */
  sessionId?: string
  /** 这份列表所依据的资料版本，回答待确认问题时作为版本检查的基准。 */
  profileVersion?: string
  pendingConfirmations?: PendingConfirmation[]
}

export function askCareerOs(candidateId: string, question: string, sessionId?: string) {
  return requestJson<AgentResponse>(`/api/v1/candidates/${candidateId}/agent-queries`, {
    method: 'POST',
    body: JSON.stringify({ question, limit: 5, sessionId: sessionId ?? null }),
  })
}
