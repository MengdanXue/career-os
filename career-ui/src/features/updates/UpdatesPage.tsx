import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { queryKeys } from '../../api/http'
import { AsyncState } from '../../components/AsyncState'
import { DocumentImportForm } from './DocumentImportForm'
import { ExcelImportForm } from './ExcelImportForm'
import { ReviewDossier } from './ReviewDossier'
import { ReviewQueue } from './ReviewQueue'
import { SourceList } from './SourceList'
import { listPendingReviews, listSources } from './updateApi'

export function UpdatesPage() {
  const sources = useQuery({ queryKey: queryKeys.sources, queryFn: listSources })
  const reviews = useQuery({ queryKey: queryKeys.reviews('PENDING'), queryFn: listPendingReviews })
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = reviews.data?.content.find(review => review.id === selectedId) ?? null
  return <main className="updates-page page-frame wide-frame"><p className="eyebrow">DATA ACQUISITION · 数据更新</p><h1>更新岗位库</h1><p className="page-intro">优先使用官方来源。采集、附件导入和公告解析会保留内容指纹，重复运行只报告真实变化。</p><section className="update-section"><header><div><p className="section-number">01 · OFFICIAL SOURCES</p><h2>官方数据源</h2></div><span>可重复运行</span></header><AsyncState loading={sources.isLoading} error={sources.error} empty={sources.isSuccess && !sources.data.length}><SourceList sources={sources.data ?? []} /></AsyncState></section><section className="update-section"><header><div><p className="section-number">02 · MANUAL IMPORT</p><h2>导入官方附件</h2></div><span>Excel / HTML / PDF</span></header><div className="import-grid"><ExcelImportForm /><DocumentImportForm /></div></section><section className="update-section"><header><div><p className="section-number">03 · REVIEW</p><h2>人工复核</h2></div><span>{reviews.data?.totalElements ?? 0} 条待处理</span></header><AsyncState loading={reviews.isLoading} error={reviews.error}><div className="review-layout"><ReviewQueue reviews={reviews.data?.content ?? []} selectedId={selectedId} onSelect={setSelectedId} />{selected ? <ReviewDossier review={selected} /> : <div className="dossier-placeholder"><strong>选择一条抽取结果</strong><p>使用表单核对公告与岗位，不需要编辑原始 JSON。</p></div>}</div></AsyncState></section></main>
}
