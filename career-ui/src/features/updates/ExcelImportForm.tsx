import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { importExcel } from './updateApi'

export function ExcelImportForm() {
  const [file, setFile] = useState<File | null>(null)
  const upload = useMutation({ mutationFn: (form: FormData) => importExcel(form) })
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!file) return
    const form = new FormData(event.currentTarget); form.set('file', file); upload.mutate(form)
  }
  return <form className="import-form" onSubmit={submit}><h3>导入官方岗位表</h3><p>适合人社局或高校公告附件中的 XLS / XLSX。</p><label>岗位表文件<input required aria-label="Excel 岗位表文件" type="file" accept=".xls,.xlsx" onChange={event => setFile(event.target.files?.[0] ?? null)} /></label><div className="form-grid"><label>Excel 公告标题<input required name="announcementTitle" /></label><label>Excel 公告来源网址<input required name="sourceUrl" type="url" /></label><label>招聘年份<input required name="recruitmentYear" type="number" defaultValue={new Date().getFullYear()} /></label><label>默认地点<input name="defaultLocation" defaultValue="浙江杭州" /></label></div><button className="primary-action" disabled={upload.isPending} type="submit">{upload.isPending ? '正在导入…' : '导入岗位表'}</button>{upload.isSuccess && <p className="import-result" role="status">岗位表已完成增量导入。</p>}{upload.isError && <p className="form-error" role="alert">岗位表没有导入，请检查列名和公告信息。</p>}</form>
}
