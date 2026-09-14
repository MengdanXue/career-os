import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { AgentRunPanel } from './AgentRunPanel'

const candidateId = '01992f09-0000-7000-8000-000000000001'

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

const run = {
  outcome: 'FINISHED',
  narrative: '下面按稳定性排序，先看前面几个。',
  question: null,
  violations: [],
  budgetSpent: 13,
  budgetLimit: 50,
  groundedIn: 2,
  sessionId: null,
  profileVersion: null,
  tools: [{
    name: 'search_jobs',
    description: '按城市、职位类别、机会分层查岗位，返回排好序的一页结果。',
    parameters: [
      { name: 'tier', required: false, description: '机会分层', allowedValues: ['T1', 'T2', 'T3'] },
      { name: 'jobId', required: true, description: '岗位的 UUID', allowedValues: [] },
    ],
  }],
  trace: [
    { tool: 'update_profile', arguments: {}, why: null, accepted: false, reason: '未注册的工具', budgetUnits: 0 },
    { tool: 'search_jobs', arguments: { tier: 'T1' }, why: '先看有没有岗位', accepted: true, reason: 'ok', budgetUnits: 13 },
  ],
  observations: [
    { tool: 'update_profile', ok: false, summary: '没有这个工具，或者它不在只读工具面里。', data: {} },
    { tool: 'search_jobs', ok: true, summary: '找到 2 个岗位。', data: { count: 2 } },
  ],
}

async function open(fetchImpl: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchImpl)
  render(<AppProviders><AgentRunPanel candidateId={candidateId} /></AppProviders>)
  await userEvent.click(screen.getByRole('button', { name: /让它自己选工具查/ }))
}

describe('AgentRunPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  /**
   * 模型没启用时明说，不拿一个写死的固定流程冒充。
   *
   * <p>后面摆一个按固定顺序调工具的东西，用户会当成"它能自己选工具"——
   * 那正是"不要把硬编码流程叫作 Agent"要防的事。
   */
  it('says the model is off instead of pretending with a fixed flow', async () => {
    await open(vi.fn().mockResolvedValue(json({ code: 'PLANNER_UNAVAILABLE', detail: '模型未启用' }, 503)))

    await userEvent.type(screen.getByLabelText('交给它去查'), '我关注的有变化吗？')
    await userEvent.click(screen.getByRole('button', { name: '运行' }))

    expect(await screen.findByText(/模型未启用/)).toBeInTheDocument()
    expect(screen.getByText(/不会用一个写死的固定流程冒充/)).toBeInTheDocument()
  })

  /** 被拒的步骤要显示。看不见的拦截等于没拦截。 */
  it('shows the refused step, not just the successful ones', async () => {
    await open(vi.fn().mockResolvedValue(json(run)))

    await userEvent.type(screen.getByLabelText('交给它去查'), '帮我改政治面貌')
    await userEvent.click(screen.getByRole('button', { name: '运行' }))

    expect(await screen.findByText('已拒绝：未注册的工具')).toBeInTheDocument()
    expect(screen.getByText('它给的理由：先看有没有岗位')).toBeInTheDocument()
  })

  /** 预算要连上限一起给，否则"花了 13"没有参照，看不出还剩多少。 */
  it('reports the budget against its limit and how much evidence backed the answer', async () => {
    await open(vi.fn().mockResolvedValue(json(run)))

    await userEvent.type(screen.getByLabelText('交给它去查'), '杭州有哪些岗位')
    await userEvent.click(screen.getByRole('button', { name: '运行' }))

    expect(await screen.findByText(/本次消耗预算 13 \/ 50 个单位/)).toBeInTheDocument()
    expect(screen.getByText(/背后有 2 条成功的工具结果/)).toBeInTheDocument()
  })

  /** 工具目录要到参数一级：调用方能核对边界，而不是只能相信它是只读的。 */
  it('lists the tool catalogue down to each parameter and its allowed values', async () => {
    await open(vi.fn().mockResolvedValue(json(run)))

    await userEvent.type(screen.getByLabelText('交给它去查'), '杭州有哪些岗位')
    await userEvent.click(screen.getByRole('button', { name: '运行' }))

    expect(await screen.findByText('只能取 T1、T2、T3')).toBeInTheDocument()
    expect(screen.getByText(/按城市、职位类别、机会分层查岗位/)).toBeInTheDocument()
    expect(screen.getByText(/必填 · 岗位的 UUID/)).toBeInTheDocument()
  })

  /** 叙述被拒时要说为什么，不能只留一段空白让人以为它什么都没查到。 */
  it('shows why the closing narrative was dropped', async () => {
    await open(vi.fn().mockResolvedValue(json({
      ...run, narrative: null,
      violations: ['没有任何成功的工具结果，这段收尾叙述没有依据'],
    })))

    await userEvent.type(screen.getByLabelText('交给它去查'), '有什么合适的')
    await userEvent.click(screen.getByRole('button', { name: '运行' }))

    expect(await screen.findByText('没有任何成功的工具结果，这段收尾叙述没有依据')).toBeInTheDocument()
  })
})
