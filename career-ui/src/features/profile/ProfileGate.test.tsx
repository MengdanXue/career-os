import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { ProfileGate } from './ProfileGate'

afterEach(() => { localStorage.clear(); vi.unstubAllGlobals() })

it('allows preliminary opportunities while some candidate facts still need confirmation', async () => {
  const candidate = {
    id: '01992f09-0000-7000-8000-000000000001', displayName: '候选人', birthDate: { year: 1992, month: 12, day: null },
    highestEducation: 'MASTER', majors: ['计算机科学与技术'], graduationYear: 2018, experienceYears: 6,
    professionalTitles: [], preferredLocations: ['杭州'], acceptedEmploymentTypes: [], profileVersion: 'seed-v1',
    skills: [], researchKeywords: [], targetJobFamilies: [], preferredOrganizationTypes: [],
  }
  localStorage.setItem(`career-os.profile-confirmed.${candidate.id}`, 'true')
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).endsWith('/api/v1/candidates')) return new Response(JSON.stringify([candidate]), { status: 200, headers: { 'Content-Type': 'application/json' } })
    return new Response(JSON.stringify({ profile: candidate, statuses: {}, confirmedCount: 0, unconfirmedCount: 6, unknownCount: 6, decisionReady: false }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))

  render(<AppProviders><ProfileGate><h1>决策首页</h1></ProfileGate></AppProviders>)

  expect(await screen.findByRole('heading', { name: '决策首页' })).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '先确认你的决策资料' })).not.toBeInTheDocument()
})
