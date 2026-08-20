import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { ProfilePage } from './ProfilePage'

const candidate = {
  id: '01992f09-0000-7000-8000-000000000001', displayName: '候选人',
  birthDate: { year: 1992, month: 12, day: null }, highestEducation: 'MASTER',
  majors: ['计算机科学与技术'], graduationYear: 2018, experienceYears: 6,
  professionalTitles: ['中级：计算机应用'], preferredLocations: ['杭州'],
  acceptedEmploymentTypes: ['ESTABLISHMENT', 'CONTRACT'], profileVersion: 'seed-v1',
  skills: ['Java', 'PostgreSQL'], researchKeywords: ['数据治理'],
  targetJobFamilies: ['INFORMATION_SYSTEMS'], preferredOrganizationTypes: ['PUBLIC_INSTITUTION'],
}

const factKeys = [
  'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR', 'EXPERIENCE_YEARS',
  'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS', 'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS',
  'RESEARCH_KEYWORDS', 'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES',
]

function facts(profile = candidate, status = 'UNCONFIRMED') {
  return {
    profile, statuses: Object.fromEntries(factKeys.map(key => [key, status])),
    confirmedCount: status === 'CONFIRMED' ? 12 : 0,
    unconfirmedCount: status === 'UNCONFIRMED' ? 12 : 0,
    unknownCount: 0, decisionReady: status === 'CONFIRMED',
  }
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
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
      if (url.endsWith('/facts') && !init?.method) return json(facts(candidate))
      if (init?.method === 'PUT') return json(saved)
      if (url.endsWith('/facts/confirm')) return json(facts(confirmed, 'CONFIRMED'))
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)
    expect(await screen.findByRole('heading', { name: '先确认你的决策资料' })).toBeInTheDocument()
    expect(screen.getByText('待确认 12')).toBeInTheDocument()
    expect(screen.getByLabelText('专业职称')).toHaveValue('中级：计算机应用')
    await userEvent.click(screen.getByRole('button', { name: '确认并开始' }))

    expect(await screen.findByRole('heading', { name: '资料已确认' })).toBeInTheDocument()
    expect(screen.getByText('已确认 12')).toBeInTheDocument()
    expect(localStorage.getItem(`career-os.profile-confirmed.${candidate.id}`)).toBeNull()
    const confirmCall = fetch.mock.calls.find(([input]) => String(input).endsWith('/facts/confirm'))
    expect(JSON.parse(String(confirmCall?.[1]?.body)).factKeys).toEqual(factKeys)
  })

  it('keeps a failed server confirmation retryable and never sends a client profile version', async () => {
    let confirmationAttempts = 0
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/candidates')) return json([candidate])
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
      if (url.endsWith(`/${selected.id}/facts`)) return json(facts(selected, 'CONFIRMED'))
      if (url.endsWith(`/${candidate.id}/facts`)) return json(facts(candidate, 'CONFIRMED'))
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)

    expect(await screen.findByText(/候选人 B ·/)).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining(`/${selected.id}/facts`), expect.anything())
  })
})
