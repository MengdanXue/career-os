import type { JobLibrarySummary } from '../../api/contracts'

export function AdmissionSummary({ summary }: { summary: JobLibrarySummary }) {
  const rawLabel = summary.raw === summary.total
    ? `${summary.raw} 条原始记录`
    : `${summary.total} 条记录，其中 ${summary.raw} 条原始`

  return <section className="admission-summary" aria-labelledby="admission-summary-title">
    <header>
      <div>
        <p className="section-number">EVIDENCE ADMISSION · 证据准入</p>
        <h2 id="admission-summary-title">岗位怎样进入机会池</h2>
      </div>
      <p>采集到不等于可以推荐</p>
    </header>
    <ol className="admission-pipeline">
      <li data-stage="library">
        <span className="admission-marker" aria-hidden="true" />
        <small>官方岗位库</small>
        <strong>{rawLabel}</strong>
        <p>保留来源与内容指纹</p>
      </li>
      <li data-stage="review">
        <span className="admission-marker" aria-hidden="true" />
        <small>分类与证据复核</small>
        <strong>{summary.needsReview} 条等待分类或证据复核</strong>
        <p>核对目标岗位与硬条件</p>
      </li>
      <li data-stage="verified">
        <span className="admission-marker" aria-hidden="true" />
        <small>完成核验</small>
        <strong>{summary.verified} 条已核验</strong>
        <p>正文变化会自动撤销核验</p>
      </li>
      <li data-stage="ready">
        <span className="admission-marker" aria-hidden="true" />
        <small>可信机会池</small>
        <strong>{summary.opportunityReady} 个可信机会</strong>
        <p>才会进入 T1 / T2 / T3</p>
      </li>
    </ol>
    <p className="admission-rule">只有“已核验”且属于目标技术岗位的记录，才参与资格判断、稳定性评分和推荐。</p>
  </section>
}
