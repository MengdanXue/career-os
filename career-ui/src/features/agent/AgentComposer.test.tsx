import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { AgentComposer } from './AgentComposer'

const candidateId = '01992f09-0000-7000-8000-000000000001'
const answer = {
  question: '本周最值得准备什么？',
  answer: '优先核对杭州市数字事业中心岗位的专业目录，并在截止日前准备证明材料。',
  decisions: [{
    decisionId: crypto.randomUUID(), candidateId, jobId: crypto.randomUUID(), jobTitle: '信息中心 Java 岗', organizationName: '杭州市数字事业中心', location: '杭州', eligibilityStatus: 'ELIGIBLE', tier: 'T1', recommendationStatus: 'RECOMMENDED',
    fit: { score: 82, coveragePercent: 75, dimensions: [] }, stability: { score: 88, coveragePercent: 60, dimensions: [] }, explanation: '资格通过', warnings: [], evidenceIds: [], evaluatorVersion: 'decision-v1', profileVersion: 'profile-v1', jobContentFingerprint: 'a'.repeat(64), assessedAt: '2026-08-20T10:00:00Z', disclaimer: '机会决策指数，不是录取概率',
  }],
  modelPhrased: false,
  fallbackUsed: false,
  disclaimer: '机会决策指数，不是录取概率',
}

function json(value: unknown) { return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } }) }

describe('AgentComposer', () => {
  beforeEach(() => { localStorage.clear(); localStorage.setItem('career-os.selected-candidate', candidateId) })
  afterEach(() => vi.unstubAllGlobals())

  it('answers with structured local decisions when no model is configured', async () => {
    const fetch = vi.fn().mockResolvedValue(json(answer))
    vi.stubGlobal('fetch', fetch)
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '本周最值得准备什么？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))

    expect(await screen.findByText(/优先核对杭州市数字事业中心/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看 信息中心 Java 岗' })).toHaveAttribute('href', expect.stringContaining('/opportunities/'))
    expect(screen.getByText('T1')).toBeInTheDocument()
  })

  it('keeps a useful local answer when optional wording enhancement is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...answer, fallbackUsed: true })))
    render(<AppProviders><AgentComposer expanded /></AppProviders>)

    await userEvent.type(screen.getByLabelText('向 Career OS 提问'), '为什么推荐这个岗位？')
    await userEvent.click(screen.getByRole('button', { name: '分析' }))

    expect(await screen.findByText('已使用本地决策规则完成回答')).toBeInTheDocument()
    expect(screen.queryByText(/LLM|OpenAI|模型失败/i)).not.toBeInTheDocument()
  })
})
