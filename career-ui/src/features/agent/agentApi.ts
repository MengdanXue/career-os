import { requestJson } from '../../api/http'
import type { JobDecision } from '../opportunities/opportunityApi'

export type AgentResponse = {
  question: string
  answer: string
  decisions: JobDecision[]
  modelPhrased: boolean
  fallbackUsed: boolean
  disclaimer: string
  /** 模型叙述被拒的原因。为空表示没有拦截发生。 */
  violations?: string[]
}

export function askCareerOs(candidateId: string, question: string) {
  return requestJson<AgentResponse>(`/api/v1/candidates/${candidateId}/agent-queries`, {
    method: 'POST',
    body: JSON.stringify({ question, limit: 5 }),
  })
}
