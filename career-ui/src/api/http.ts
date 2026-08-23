import type { ProblemDetail } from './contracts'

export class ApiProblem extends Error {
  readonly code: string
  readonly status: number
  readonly title: string
  readonly type?: string

  constructor(input: { code: string; status: number; title: string; message: string; type?: string }) {
    super(input.message)
    this.name = 'ApiProblem'
    this.code = input.code
    this.status = input.status
    this.title = input.title
    this.type = input.type
  }
}

function isJsonResponse(response: Response) {
  return response.headers.get('content-type')?.includes('json') ?? false
}

async function toApiProblem(response: Response): Promise<ApiProblem> {
  let problem: ProblemDetail = {}
  if (isJsonResponse(response)) {
    try {
      problem = await response.json() as ProblemDetail
    } catch {
      problem = {}
    }
  }
  return new ApiProblem({
    code: problem.code ?? `HTTP_${response.status}`,
    status: response.status,
    title: problem.title ?? '请求未完成',
    message: problem.detail ?? 'Career OS 没有完成这次请求，请稍后重试。',
    type: problem.type,
  })
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const hasBody = init.body !== undefined && init.body !== null
  if (hasBody && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  headers.set('Accept', 'application/json')

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch {
    throw new ApiProblem({
      code: 'NETWORK_UNAVAILABLE',
      status: 0,
      title: '本地服务未连接',
      message: 'Career OS 暂时无法连接本地服务，请确认服务已启动后重试。',
    })
  }

  if (!response.ok) throw await toApiProblem(response)
  if (response.status === 204) return undefined as T
  return await response.json() as T
}

export const queryKeys = {
  candidate: (candidateId: string) => ['candidate', candidateId] as const,
  decisions: (candidateId: string, filters: Record<string, unknown>) => ['decisions', candidateId, filters] as const,
  candidateMatches: (candidateId: string) => ['candidate-matches', candidateId] as const,
  workbench: (candidateId: string) => ['workbench', candidateId] as const,
  personalActions: (candidateId: string, asOf: string) => ['personal-actions', candidateId, asOf] as const,
  careerPlan: (candidateId: string, targetYear: number) => ['career-plan', candidateId, targetYear] as const,
  planningJob: (jobId: string) => ['planning-job', jobId] as const,
  sources: ['acquisition-sources'] as const,
  jobLibrarySummary: ['job-library-summary'] as const,
  reviews: (status: string) => ['reviews', status] as const,
}
