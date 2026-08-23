import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { ProfilePage } from './ProfilePage'

const candidate = {
  id: '01992f09-0000-7000-8000-000000000001', displayName: '候选人',
  birthDate: { year: 1992, month: 12, day: 31 }, gender: 'FEMALE', politicalAffiliation: 'UNKNOWN', highestEducation: 'BACHELOR',
  majors: ['计算机科学与技术'], graduationYear: 2014, experienceYears: 6,
  professionalTitles: ['中级：计算机应用'], preferredLocations: ['杭州'],
  acceptedEmploymentTypes: ['ESTABLISHMENT', 'CONTRACT'], profileVersion: 'seed-v1',
  skills: ['Java', 'PostgreSQL'], researchKeywords: ['数据治理'],
  targetJobFamilies: ['INFORMATION_SYSTEMS'], preferredOrganizationTypes: ['PUBLIC_INSTITUTION'],
  educationRecords: [
    { institutionName: null, countryOrRegion: null, educationLevel: 'BACHELOR', majorName: '计算机科学与技术', graduationYear: 2014, graduationMonth: null, completionStatus: 'COMPLETED', credentialVerificationStatus: 'UNKNOWN' },
    { institutionName: '示例海外大学', countryOrRegion: '示例国', educationLevel: 'MASTER', majorName: '计算机科学', graduationYear: 2027, graduationMonth: null, completionStatus: 'EXPECTED', credentialVerificationStatus: 'PLANNED' },
  ],
  employmentRecords: [],
}

const factKeys = [
  'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR', 'EXPERIENCE_YEARS',
  'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS', 'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS',
  'RESEARCH_KEYWORDS', 'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES',
  'EDUCATION_RECORDS', 'GENDER', 'POLITICAL_AFFILIATION', 'EMPLOYMENT_HISTORY',
]

function facts(profile = candidate, status = 'UNCONFIRMED') {
  return {
    profile, statuses: Object.fromEntries(factKeys.map(key => [key, status])),
    confirmedCount: status === 'CONFIRMED' ? 16 : 0,
    unconfirmedCount: status === 'UNCONFIRMED' ? 16 : 0,
    unknownCount: 0, decisionReady: status === 'CONFIRMED',
  }
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

function evidenceTasks(items: unknown[] = []) {
  return { candidateId: candidate.id, asOf: '2026-08-23', available: true, message: null, items }
}

describe('ProfilePage', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  it('uses server fact state and confirms the current profile with one action', async () => {
    const saved = { ...candidate, profileVersion: 'profile-server-draft' }
    const confirmed = { ...candidate, profileVersion: 'profile-server-confirmed' }
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/candidates')) return json([candidate])
      if (url.includes('/evidence-tasks')) return json(evidenceTasks())
      if (url.endsWith('/facts') && !init?.method) return json(facts(candidate))
      if (init?.method === 'PUT') return json(saved)
      if (url.endsWith('/facts/confirm')) return json(facts(confirmed, 'CONFIRMED'))
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)
    expect(await screen.findByRole('heading', { name: '先确认你的决策资料' })).toBeInTheDocument()
    expect(screen.getByText('待确认 16')).toBeInTheDocument()
    expect(screen.getByLabelText('出生日期')).toHaveValue('1992-12-31')
    expect(screen.getByLabelText('性别')).toHaveValue('FEMALE')
    expect(screen.getByLabelText('政治面貌')).toHaveValue('UNKNOWN')
    expect(screen.getByRole('heading', { name: '可核验工作经历' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('示例海外大学')).toBeInTheDocument()
    expect(screen.getByLabelText('教育专业 1')).toHaveValue('计算机科学与技术')
    expect(screen.getByLabelText('专业职称')).toHaveValue('中级：计算机应用')
    await userEvent.click(screen.getByRole('button', { name: '确认并开始' }))

    expect(await screen.findByRole('heading', { name: '资料已确认' })).toBeInTheDocument()
    expect(screen.getByText(/候选人 · 本科 · 计算机科学与技术/)).toBeInTheDocument()
    expect(screen.getByText('已确认 16')).toBeInTheDocument()
    expect(screen.getByText(/本科 · 计算机科学与技术 · 2014 · 已毕业/)).toBeInTheDocument()
    expect(screen.getByText(/示例海外大学 · 硕士 · 计算机科学 · 2027 · 预计毕业/)).toBeInTheDocument()
    expect(localStorage.getItem(`career-os.profile-confirmed.${candidate.id}`)).toBeNull()
    const confirmCall = fetch.mock.calls.find(([input]) => String(input).endsWith('/facts/confirm'))
    expect(JSON.parse(String(confirmCall?.[1]?.body)).factKeys).toEqual(factKeys)
    const updateCall = fetch.mock.calls.find(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(String(updateCall?.[1]?.body)).educationRecords).toHaveLength(2)
    expect(JSON.parse(String(updateCall?.[1]?.body))).toMatchObject({
      birthDay: 31, gender: 'FEMALE', politicalAffiliation: 'UNKNOWN', employmentRecords: [],
    })
  })

  it('keeps a failed server confirmation retryable and never sends a client profile version', async () => {
    let confirmationAttempts = 0
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/candidates')) return json([candidate])
      if (url.includes('/evidence-tasks')) return json(evidenceTasks())
      if (url.endsWith('/facts') && !init?.method) return json(facts(candidate, 'CONFIRMED'))
      if (init?.method === 'PUT') return json({ ...candidate, ...JSON.parse(String(init.body)), profileVersion: `profile-server-${confirmationAttempts}` })
      if (url.endsWith('/facts/confirm')) {
        confirmationAttempts += 1
        if (confirmationAttempts === 1) return json({ title: 'Temporary', detail: '确认暂时失败', code: 'TEMPORARY' }, 503)
        return json(facts({ ...candidate, skills: ['Java', 'Spring Boot'], profileVersion: 'profile-server-final' }, 'CONFIRMED'))
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)
    await userEvent.click(await screen.findByRole('button', { name: '修改资料' }))
    fireEvent.change(screen.getByLabelText('技能关键词'), { target: { value: 'Java, Spring Boot' } })
    await userEvent.click(screen.getByRole('button', { name: '保存并重新确认' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('确认暂时失败')
    await userEvent.click(screen.getByRole('button', { name: '重新提交' }))
    expect(await screen.findByRole('heading', { name: '资料已确认' })).toBeInTheDocument()
    expect(confirmationAttempts).toBe(2)
    const putBodies = fetch.mock.calls.filter(([, init]) => init?.method === 'PUT').map(([, init]) => JSON.parse(String(init?.body)))
    expect(putBodies).not.toHaveLength(0)
    expect(putBodies.every(body => !('profileVersion' in body))).toBe(true)
  })

  it('edits the candidate selected by the decision gate when multiple profiles exist', async () => {
    const selected = { ...candidate, id: '01992f09-0000-7000-8000-000000000002', displayName: '候选人 B' }
    localStorage.setItem('career-os.selected-candidate', selected.id)
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/v1/candidates')) return json([candidate, selected])
      if (url.includes('/evidence-tasks')) return json({ ...evidenceTasks(), candidateId: selected.id })
      if (url.endsWith(`/${selected.id}/facts`)) return json(facts(selected, 'CONFIRMED'))
      if (url.endsWith(`/${candidate.id}/facts`)) return json(facts(candidate, 'CONFIRMED'))
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)

    expect(await screen.findByText(/候选人 B ·/)).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining(`/${selected.id}/facts`), expect.anything())
  })

  it('puts the highest-impact evidence gaps first and never presents legacy years as verified', async () => {
    const incompleteCandidate = {
      ...candidate, experienceYears: 7, politicalAffiliation: 'NON_MEMBER', skills: [], researchKeywords: [],
    }
    const mixedFacts = {
      ...facts(incompleteCandidate, 'CONFIRMED'),
      statuses: {
        ...facts(incompleteCandidate, 'CONFIRMED').statuses,
        EXPERIENCE_YEARS: 'UNKNOWN', EMPLOYMENT_HISTORY: 'UNKNOWN', POLITICAL_AFFILIATION: 'UNKNOWN',
        SKILLS: 'UNKNOWN', RESEARCH_KEYWORDS: 'UNKNOWN',
      },
      confirmedCount: 11, unknownCount: 5, decisionReady: true,
    }
    const tasks = evidenceTasks([
      {
        code: 'VERIFY_EMPLOYMENT_HISTORY', kind: 'EMPLOYMENT', factKey: 'EMPLOYMENT_HISTORY',
        title: '补齐可核验工作经历', reason: '旧资料记录 7 年，但硬资格只能使用逐段核验的工作经历。',
        affectedJobCount: 12, evidenceStrength: 'NONE', deepLink: '/profile#employment-history',
      },
      {
        code: 'CONFIRM_POLITICAL_AFFILIATION', kind: 'POLITICAL_AFFILIATION', factKey: 'POLITICAL_AFFILIATION',
        title: '确认政治面貌', reason: '当前值没有被证据状态确认。',
        affectedJobCount: 2, evidenceStrength: 'SELF_REPORTED', deepLink: '/profile#political-affiliation',
      },
    ])
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/v1/candidates')) return json([incompleteCandidate])
      if (url.includes('/evidence-tasks')) return json(tasks)
      if (url.endsWith('/facts')) return json(mixedFacts)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '最影响资格的缺口' })).toBeInTheDocument()
    const taskList = screen.getByLabelText('资格证据任务')
    expect(within(taskList).getAllByRole('article')[0]).toHaveTextContent('补齐可核验工作经历')
    expect(within(taskList).getAllByRole('article')[0]).toHaveTextContent('影响 12 个岗位')
    expect(screen.getByText('旧资料记录 7 年；硬资格仍需逐段核验')).toBeInTheDocument()
    expect(screen.getByText('待确认（旧值：非中共党员）')).toBeInTheDocument()
    expect(screen.getByText('尚未提供')).toBeInTheDocument()

    await userEvent.click(within(taskList).getAllByRole('button', { name: '处理这项' })[0])

    expect(await screen.findByRole('heading', { name: '修改你的决策资料' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '可核验工作经历' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '添加一段经历' })).toHaveFocus()
  })
})
