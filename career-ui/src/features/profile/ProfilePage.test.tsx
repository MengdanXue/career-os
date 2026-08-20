import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { ProfilePage } from './ProfilePage'

const candidate = {
  id: '01992f09-0000-7000-8000-000000000001',
  displayName: '候选人',
  birthDate: { year: 1992, month: 12, day: null },
  highestEducation: 'MASTER',
  majors: ['计算机科学与技术'],
  graduationYear: 2018,
  experienceYears: 6,
  professionalTitles: [],
  preferredLocations: ['杭州'],
  acceptedEmploymentTypes: ['ESTABLISHMENT', 'CONTRACT'],
  profileVersion: 'seed-v1',
  skills: ['Java', 'PostgreSQL'],
  researchKeywords: ['数据治理'],
  targetJobFamilies: ['INFORMATION_SYSTEMS'],
  preferredOrganizationTypes: ['PUBLIC_INSTITUTION'],
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('ProfilePage', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  it('asks the user to confirm the seeded decision facts before using them', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json([candidate]))
      .mockResolvedValueOnce(json({ ...candidate, profileVersion: 'saved-v1' }))
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)

    expect(await screen.findByRole('heading', { name: '先确认你的决策资料' })).toBeInTheDocument()
    expect(screen.getByLabelText('专业')).toHaveValue('计算机科学与技术')
    expect(screen.getByLabelText('目标地点')).toHaveValue('杭州')
    await userEvent.click(screen.getByRole('button', { name: '确认并开始' }))

    expect(await screen.findByRole('heading', { name: '资料已确认' })).toBeInTheDocument()
    expect(localStorage.getItem(`career-os.profile-confirmed.${candidate.id}`)).toBe('true')
  })

  it('reuses a version across a failed retry and creates one for the next edit session', async () => {
    const versions: string[] = []
    let putCount = 0
    const fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (!init?.method) return json([candidate])
      const body = JSON.parse(String(init.body)) as { profileVersion: string }
      versions.push(body.profileVersion)
      putCount += 1
      if (putCount === 1) return json({ title: 'Temporary', detail: 'retry', code: 'TEMPORARY' }, 503)
      return json({ ...candidate, ...body })
    })
    vi.stubGlobal('fetch', fetch)

    render(<AppProviders><ProfilePage /></AppProviders>)
    await userEvent.click(await screen.findByRole('button', { name: '确认并开始' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('retry')
    await userEvent.click(screen.getByRole('button', { name: '重新提交' }))
    expect(await screen.findByRole('heading', { name: '资料已确认' })).toBeInTheDocument()

    expect(versions[0]).toMatch(/^profile-ui-[0-9a-f-]+$/)
    expect(versions[1]).toBe(versions[0])

    await userEvent.click(screen.getByRole('button', { name: '修改资料' }))
    fireEvent.change(screen.getByLabelText('技能关键词'), { target: { value: 'Java, PostgreSQL, Spring Boot' } })
    await userEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() => expect(versions).toHaveLength(3))
    expect(versions[2]).not.toBe(versions[1])
  })
})
