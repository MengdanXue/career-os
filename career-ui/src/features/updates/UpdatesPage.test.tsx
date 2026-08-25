import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { UpdatesPage } from './UpdatesPage'

const source = { id: '11111111-1111-1111-1111-111111111111', code: 'hangzhou-hrss', name: '杭州市人社局', entryUri: 'https://example.gov.cn', sourceType: 'OFFICIAL_GOVERNMENT', region: '浙江杭州', crawlMode: 'STATIC_HTML', enabled: true, cronExpression: '0 0 8 * * *', timeZone: 'Asia/Shanghai', lastSuccessAt: '2026-08-24T12:00:00Z', lastFailureAt: null, nextDueAt: null, consecutiveFailureCount: 0, connectionStatus: 'PARTIAL', scopeLevel: 'CITY', scopeCode: 'HANGZHOU', priorityTier: 'P0', coverageRole: 'PRIMARY', accessStatus: 'ACCESSIBLE', documentIssueCount: 2, lifecycleDocumentCount: 7, matchedLifecycleCount: 4, unmatchedLifecycleCount: 2, ambiguousLifecycleCount: 1, historicalFailureCount: 2, checkpoints: [{ sourceId: '11111111-1111-1111-1111-111111111111', checkpoint: 'REGISTERED', status: 'VERIFIED', evidence: '官方来源已登记', verifiedAt: '2026-08-24T12:00:00Z' }], coverage: [{ sourceId: '11111111-1111-1111-1111-111111111111', year: 2024, status: 'PARTIAL', discoveredCount: 3, fetchedCount: 2, parsedCount: 2, targetJobCount: 1, completionBasis: null, completedAt: null, updatedAt: '2026-08-24T12:00:00Z', supportsAbsenceConclusion: false, listingPageCount: 2, filteredCount: 1, failedCount: 1, earliestPublishedOn: '2024-03-01', latestPublishedOn: '2024-08-01', stopReason: 'DOCUMENT_FAILURE' }] }
const admissionSummary = {
  total: 2291, raw: 2291, parsed: 0, normalized: 0,
  reviewRequired: 0, verified: 0, rejected: 0, failed: 0,
  included: 0, excluded: 0, needsReview: 2291, opportunityReady: 0,
}
const fact = (value: unknown) => ({ value, factStatus: value == null ? 'UNKNOWN' : 'EXPLICIT', confidence: value == null ? 0 : .9, evidenceFragmentIds: [], interpretation: null })
const proposal = {
  schemaVersion: '1.0.0', source: { evidenceId: '22222222-2222-2222-2222-222222222222', sourceUrl: 'https://example.gov.cn/notice', sourceTitle: '2026 年招聘公告' },
  organization: { name: '杭州市数字事业中心', organizationType: fact('PUBLIC_INSTITUTION') },
  recruitmentEvent: { title: '2026 年招聘公告', recruitmentYear: 2026, eventType: 'PUBLIC_INSTITUTION', publishedOn: fact('2026-08-01'), applicationStartsOn: fact('2026-08-20'), applicationEndsOn: fact('2026-08-31') },
  jobs: [{ title: fact('信息中心 Java 岗'), externalJobCode: 'A01', headcount: fact(1), employmentType: fact('ESTABLISHMENT'), location: '杭州', minimumEducation: fact('MASTER'), degree: fact('硕士'), majorText: fact('计算机科学与技术'), maximumAge: fact(38), acceptedGraduationYears: fact(null), minimumExperienceYears: fact(null), jobFamily: 'INFORMATION_SYSTEMS', duties: '数据平台建设' }],
  warnings: [], confidence: .88, completeSnapshot: true,
}
const review = { id: '33333333-3333-3333-3333-333333333333', extractionRunId: '44444444-4444-4444-4444-444444444444', status: 'PENDING', version: 4, proposal, issues: [], actions: [], createdAt: '2026-08-20T08:00:00Z', resolvedAt: null }

function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } }) }
function defaults(input: RequestInfo | URL) {
  const url = String(input)
  if (url.includes('/api/v1/job-library/summary')) return json(admissionSummary)
  if (url.includes('/api/acquisition/sources')) return json([source])
  if (url.includes('/api/v1/reviews')) return json({ content: [review], page: 0, size: 20, totalElements: 1 })
  throw new Error(`Unexpected request ${url}`)
}

describe('UpdatesPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('distinguishes raw records from verified opportunities', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => defaults(input)))

    render(<AppProviders><UpdatesPage /></AppProviders>)

    expect(await screen.findByText('2291 条原始记录')).toBeInTheDocument()
    expect(screen.getByText('2291 条等待分类或证据复核')).toBeInTheDocument()
    expect(screen.getByText('0 个可信机会')).toBeInTheDocument()
  })

  it('shows truthful source coverage and unresolved parsing work', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => defaults(input)))

    render(<AppProviders><UpdatesPage /></AppProviders>)

    expect(await screen.findByText('部分接入')).toBeVisible()
    expect(screen.getByText(/2024：部分完成 · 公告 3 · 岗位 1 · 失败 1/)).toBeVisible()
    expect(screen.getByText('官网访问正常 · 2 个附件问题记录')).toBeVisible()
    expect(screen.getByText('后续公告 7 · 已关联 4 · 待关联 2 · 歧义 1')).toBeVisible()
    expect(screen.getByText('P0 · 杭州')).toBeVisible()
    expect(screen.getByText('历史采集失败记录 2 条')).toBeVisible()
    expect(screen.queryByText('来源正常')).not.toBeInTheDocument()
  })

  it('does not present a partially successful source run as full success', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => init?.method === 'POST' && String(input).includes('/runs')
      ? json({ id: crypto.randomUUID(), sourceId: source.id, trigger: 'MANUAL', status: 'PARTIALLY_SUCCEEDED', startedAt: '2026-08-20T08:00:00Z', completedAt: '2026-08-20T08:01:00Z', discoveredCount: 5, fetchedCount: 4, unchangedCount: 2, addedCount: 1, updatedCount: 1, deactivatedCount: 0, failedCount: 1, errorCode: 'ONE_DOCUMENT_FAILED', errorMessage: 'one failed' }, 202)
      : defaults(input)))

    render(<AppProviders><UpdatesPage /></AppProviders>)
    await userEvent.click(await screen.findByRole('button', { name: '运行 杭州市人社局' }, { timeout: 5_000 }))
    expect(await screen.findByText('部分完成：新增 1，更新 1，失败 1', {}, { timeout: 5_000 })).toBeInTheDocument()
    expect(screen.queryByText('更新成功')).not.toBeInTheDocument()
  })

  it('reports an idempotently reused document instead of claiming a new import', async () => {
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => init?.method === 'POST' && String(input).endsWith('/api/v1/extractions')
      ? json({ id: crypto.randomUUID(), status: 'REVIEW_REQUIRED', reused: true, evidenceId: crypto.randomUUID(), reviewId: review.id, proposal, errorCode: null, errorMessage: null })
      : defaults(input))
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><UpdatesPage /></AppProviders>)

    await userEvent.type(await screen.findByLabelText('公告来源网址'), 'https://example.gov.cn/notice')
    await userEvent.type(screen.getByLabelText('公告标题'), '2026 年招聘公告')
    const documentInput = screen.getByLabelText('HTML 或 PDF 文件') as HTMLInputElement
    await userEvent.upload(documentInput, new File(['<html><body>notice</body></html>'], 'notice.html', { type: 'text/html' }))
    expect(documentInput.files).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: '解析公告' }))

    await waitFor(() => expect(fetch.mock.calls.some(([url], index) => String(url).endsWith('/api/v1/extractions') && fetch.mock.calls[index]?.[1]?.method === 'POST')).toBe(true))
    expect(await screen.findByText('这份文件已处理过，已打开原有结果。')).toBeInTheDocument()
  })

  it('confirms a review using its visible optimistic version', async () => {
    let actionBody: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST' && String(input).includes('/actions')) { actionBody = JSON.parse(String(init.body)); return json({ item: { ...review, status: 'RESOLVED', version: 5 }, run: {}, fragments: [] }) }
      return defaults(input)
    }))
    render(<AppProviders><UpdatesPage /></AppProviders>)

    await userEvent.click(await screen.findByRole('button', { name: '查看复核 2026 年招聘公告' }))
    await userEvent.click(screen.getByRole('button', { name: '确认并入库' }))
    await waitFor(() => expect(actionBody).toBeDefined())
    expect(actionBody).toMatchObject({ decision: 'CONFIRM', expectedVersion: 4, correctedPayload: null })
  })

  it('refreshes a conflicted review without erasing an entered correction', async () => {
    let listCalls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/v1/reviews') && !init?.method) { listCalls += 1; return json({ content: [{ ...review, version: listCalls > 1 ? 5 : 4 }], page: 0, size: 20, totalElements: 1 }) }
      if (init?.method === 'POST' && url.includes('/actions')) return json({ title: 'Review conflict', detail: 'Review version conflict', code: 'REVIEW_CONFLICT' }, 409)
      return defaults(input)
    }))
    render(<AppProviders><UpdatesPage /></AppProviders>)

    await userEvent.click(await screen.findByRole('button', { name: '查看复核 2026 年招聘公告' }))
    fireEvent.change(screen.getByLabelText('公告标题修正'), { target: { value: '2026 年正式招聘公告' } })
    await userEvent.click(screen.getByRole('button', { name: '修正并入库' }))

    expect(await screen.findByText('复核已被其他操作更新，请核对新版本后再次提交。')).toBeInTheDocument()
    expect(screen.getByLabelText('公告标题修正')).toHaveValue('2026 年正式招聘公告')
    await waitFor(() => expect(listCalls).toBeGreaterThan(1))
  })
})
