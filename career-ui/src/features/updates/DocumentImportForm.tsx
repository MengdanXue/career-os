import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { extractDocument } from './updateApi'

export function DocumentImportForm() {
  const [file, setFile] = useState<File | null>(null)
  const [sourceUrl, setSourceUrl] = useState('')
  const [sourceTitle, setSourceTitle] = useState('')
  const extraction = useMutation({ mutationFn: () => extractDocument(file!, sourceUrl, sourceTitle) })
  function submit(event: FormEvent) { event.preventDefault(); if (file) extraction.mutate() }
  return <form className="import-form" onSubmit={submit}><h3>解析公告正文</h3><p>适合 HTML 或 PDF 公告；低置信度内容会进入人工复核。</p><label>HTML 或 PDF 文件<input aria-label="HTML 或 PDF 文件" type="file" accept="text/html,application/pdf,.html,.pdf" onChange={event => setFile(event.target.files?.[0] ?? null)} /></label><label>公告来源网址<input required aria-label="公告来源网址" type="url" value={sourceUrl} onChange={event => setSourceUrl(event.target.value)} /></label><label>公告标题<input required aria-label="公告标题" value={sourceTitle} onChange={event => setSourceTitle(event.target.value)} /></label><button className="primary-action" disabled={!file || extraction.isPending} type="submit">{extraction.isPending ? '正在解析…' : '解析公告'}</button>{extraction.data && <p className="import-result" role="status">{extraction.data.reused ? '这份文件已处理过，已打开原有结果。' : extraction.data.reviewId ? '解析完成，已进入人工复核。' : '解析并验证完成。'}</p>}{extraction.isError && <p className="form-error" role="alert">公告没有解析成功，请确认文件格式和来源信息。</p>}</form>
}
