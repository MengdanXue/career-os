import { useMutation } from '@tanstack/react-query'
import { StatusChip } from '../../components/StatusChip'
import { triggerSource, type AcquisitionRun, type AcquisitionSource } from './updateApi'

function runMessage(run: AcquisitionRun) {
  if (run.status === 'PARTIALLY_SUCCEEDED') return `部分完成：新增 ${run.addedCount}，更新 ${run.updatedCount}，失败 ${run.failedCount}`
  if (run.status === 'SUCCEEDED') return `完成：新增 ${run.addedCount}，更新 ${run.updatedCount}，下线 ${run.deactivatedCount}`
  if (run.status === 'FAILED') return `运行失败：${run.errorMessage ?? run.errorCode ?? '请检查来源'}`
  if (run.status === 'RUNNING') return '仍在运行，可稍后刷新查看'
  return '本次运行被跳过，已有任务正在执行'
}

function SourceRow({ source }: { source: AcquisitionSource }) {
  const run = useMutation({ mutationFn: () => triggerSource(source.id) })
  return <article className="source-row"><div><div><StatusChip tone={source.consecutiveFailureCount ? 'warning' : 'positive'}>{source.consecutiveFailureCount ? `连续失败 ${source.consecutiveFailureCount}` : '来源正常'}</StatusChip><span>{source.region}</span></div><strong>{source.name}</strong><a href={source.entryUri} target="_blank" rel="noreferrer">查看官方来源</a></div><button type="button" aria-label={`运行 ${source.name}`} disabled={run.isPending || !source.enabled} onClick={() => run.mutate()}>{run.isPending ? '正在采集…' : '立即更新'}</button>{run.data && <p className={`run-result ${run.data.status.toLowerCase()}`} role="status">{runMessage(run.data)}</p>}{run.isError && <p className="run-result failed" role="alert">运行没有完成，请稍后重试。</p>}</article>
}

export function SourceList({ sources }: { sources: AcquisitionSource[] }) {
  return <div className="source-list">{sources.map(source => <SourceRow key={source.id} source={source} />)}</div>
}
