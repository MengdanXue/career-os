import { useMutation } from '@tanstack/react-query'
import { StatusChip } from '../../components/StatusChip'
import { triggerSource, type AcquisitionRun, type AcquisitionSource } from './updateApi'

const connectionPresentation = {
  CONNECTED: { label: '已接入', tone: 'positive' as const },
  PARTIAL: { label: '部分接入', tone: 'warning' as const },
  FAILED: { label: '接入失败', tone: 'warning' as const },
  NOT_CONNECTED: { label: '未接入', tone: 'neutral' as const },
}

const coverageLabels: Record<string, string> = {
  COMPLETE: '历史记录（未独立审计）',
  NO_TARGET_RECORDS: '历史记录（未独立审计）',
  PARTIAL: '部分完成',
  ACCESS_FAILED: '访问失败',
  NOT_DISCOVERED: '未采集',
}

const accessLabels: Record<AcquisitionSource['accessStatus'], string> = {
  ACCESSIBLE: '官网访问正常',
  ACCESS_FAILED: '官网访问失败',
  NOT_CONFIGURED: '采集器未接入',
  UNKNOWN: '官网尚未验证',
}

function scopeLabel(source: AcquisitionSource) {
  if (source.scopeCode === 'HANGZHOU') return '杭州'
  return source.region.replace(/^浙江/, '').replace(/^杭州/, '') || source.scopeCode
}

function runMessage(run: AcquisitionRun) {
  if (run.status === 'PARTIALLY_SUCCEEDED') return `部分完成：新增 ${run.addedCount}，更新 ${run.updatedCount}，失败 ${run.failedCount}`
  if (run.status === 'SUCCEEDED') return `完成：新增 ${run.addedCount}，更新 ${run.updatedCount}，下线 ${run.deactivatedCount}`
  if (run.status === 'FAILED') return `运行失败：${run.errorMessage ?? run.errorCode ?? '请检查来源'}`
  if (run.status === 'RUNNING') return '仍在运行，可稍后刷新查看'
  return '本次运行被跳过，已有任务正在执行'
}

function SourceRow({ source }: { source: AcquisitionSource }) {
  const run = useMutation({ mutationFn: () => triggerSource(source.id!) })
  const connection = connectionPresentation[source.connectionStatus] ?? connectionPresentation.NOT_CONNECTED
  const coverage = [...(source.coverage ?? [])].sort((left, right) => left.year - right.year)
  const assessment = source.completion
  return <article className="source-row">
    <div className="source-identity">
      <div><StatusChip tone={connection.tone}>{connection.label}</StatusChip><span>{source.priorityTier} · {scopeLabel(source)}</span></div>
      <strong>{source.name}</strong>
      <a href={source.entryUri} target="_blank" rel="noreferrer">查看官方来源</a>
    </div>
    <button type="button" aria-label={`运行 ${source.name}`} disabled={run.isPending || !source.enabled || !source.id} onClick={() => run.mutate()}>{run.isPending ? '正在采集…' : '立即更新'}</button>
    <div className="source-evidence" aria-label={`${source.name}采集证据`}>
      <span className="evidence-kicker">年度证据覆盖</span>
      <div className="coverage-rail">
        {coverage.length > 0 ? coverage.map(item => <span className="coverage-year" data-status={item.status.toLowerCase()} key={item.year} title={item.completionBasis ?? item.stopReason ?? undefined}>{item.year}：{coverageLabels[item.status] ?? item.status} · 公告 {item.discoveredCount} · 岗位 {item.targetJobCount}{item.failedCount > 0 ? ` · 失败 ${item.failedCount}` : ''}</span>) : <span className="coverage-year" data-status="not_discovered">尚无年度采集证据</span>}
      </div>
      {coverage.some(item => item.stopReason === 'FIXED_EVIDENCE_SET') && <span className="coverage-limitation">固定公告证据不能证明该年度官网列表已完整遍历</span>}
      <div className="source-health">
        <span>六级审计：{assessment?.status === 'PASS' ? `通过（L${assessment.level ?? 0}）` : assessment?.status === 'FAIL' ? '失败' : '未知，尚未形成独立证明'}</span>
        <span>{accessLabels[source.accessStatus]}{source.documentIssueCount > 0 ? ` · 历史附件问题记录 ${source.documentIssueCount} 条` : ''}</span>
        {source.id && <span>后续公告 {source.lifecycleDocumentCount} · 已关联 {source.matchedLifecycleCount} · 待关联 {source.unmatchedLifecycleCount} · 歧义 {source.ambiguousLifecycleCount}</span>}
        {source.historicalFailureCount > 0 && <span>历史采集失败记录 {source.historicalFailureCount} 条</span>}
        {source.consecutiveFailureCount > 0 && <span>连续运行失败 {source.consecutiveFailureCount} 次</span>}
      </div>
    </div>
    {run.data && <p className={`run-result ${run.data.status.toLowerCase()}`} role="status">{runMessage(run.data)}</p>}
    {run.isError && <p className="run-result failed" role="alert">运行没有完成，请稍后重试。</p>}
  </article>
}

export function SourceList({ sources }: { sources: AcquisitionSource[] }) {
  return <div className="source-list">{sources.map(source => <SourceRow key={source.code} source={source} />)}</div>
}
