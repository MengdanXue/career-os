import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiProblem, requestJson } from './http'

describe('requestJson', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('preserves the stable backend problem code for actionable errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      type: 'https://career-os.local/problems/candidate-not-found',
      title: 'Candidate not found',
      status: 404,
      detail: 'Candidate not found',
      code: 'CANDIDATE_NOT_FOUND',
    }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } })))

    await expect(requestJson('/api/v1/candidates/missing')).rejects.toMatchObject({
      code: 'CANDIDATE_NOT_FOUND',
      status: 404,
      title: 'Candidate not found',
    })
  })

  it('turns a disconnected backend into a safe local recovery message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const failure = requestJson('/api/v1/candidates')
    await expect(failure).rejects.toBeInstanceOf(ApiProblem)
    await expect(failure).rejects.toMatchObject({
      code: 'NETWORK_UNAVAILABLE',
      status: 0,
      message: 'Career OS 暂时无法连接本地服务，请确认服务已启动后重试。',
    })
  })
})
