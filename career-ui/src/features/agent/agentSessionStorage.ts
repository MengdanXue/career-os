import type { AgentResponse } from './agentApi'

export type AgentSnapshot = AgentResponse & { canonicalJobIdsInOrder?: string[] }

// 标签页快照不是当前结论；恢复展示前必须读取服务端会话并检查归属、岗位顺序与版本。
export function agentSessionKey(candidateId: string) { return `career-os.agent-session.v1:${candidateId}` }

export function readAgentSnapshot(candidateId: string): AgentSnapshot | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(agentSessionKey(candidateId)) ?? 'null')
    if (!value || typeof value.sessionId !== 'string' || typeof value.answer !== 'string'
      || typeof value.question !== 'string' || !Array.isArray(value.decisions)) return null
    if (value.decisions.some((item: { candidateId?: string; jobId?: string } | null) =>
      !item || item.candidateId !== candidateId || typeof item.jobId !== 'string')) return null
    // Legacy snapshots retain their original reference, but have no verified order.
    // Never infer that missing order from whatever the server happens to return later.
    if (value.canonicalJobIdsInOrder !== undefined && (!Array.isArray(value.canonicalJobIdsInOrder)
      || value.canonicalJobIdsInOrder.some((id: unknown) => typeof id !== 'string'))) delete value.canonicalJobIdsInOrder
    return value as AgentSnapshot
  } catch { return null }
}

export function saveAgentSnapshot(candidateId: string, result: AgentSnapshot | null) {
  if (result?.sessionId) sessionStorage.setItem(agentSessionKey(candidateId), JSON.stringify(result))
  else sessionStorage.removeItem(agentSessionKey(candidateId))
}
