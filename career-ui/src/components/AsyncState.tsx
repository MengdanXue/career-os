import type { ReactNode } from 'react'
import { ApiProblem } from '../api/http'

export function AsyncState({ loading, error, empty, children }: { loading: boolean; error?: unknown; empty?: boolean; children: ReactNode }) {
  if (loading) return <p className="async-state" role="status">正在整理决策信息…</p>
  if (error) {
    const message = error instanceof ApiProblem ? error.message : '这部分信息暂时无法读取，请稍后重试。'
    return <div className="async-state error" role="alert"><strong>暂时无法显示</strong><p>{message}</p></div>
  }
  if (empty) return <div className="async-state"><strong>现在没有需要处理的内容</strong><p>Career OS 会在岗位或资料变化时把它放到这里。</p></div>
  return <>{children}</>
}
