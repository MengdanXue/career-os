import { Route, Routes } from 'react-router-dom'
import { AppProviders } from './app/AppProviders'
import { AppShell } from './app/AppShell'
import { ProfileGate } from './features/profile/ProfileGate'
import { ProfilePage } from './features/profile/ProfilePage'
import { OpportunitiesPage } from './features/opportunities/OpportunitiesPage'
import { TodayPage } from './features/today/TodayPage'
import { UpdatesPage } from './features/updates/UpdatesPage'
import { CareerPlanPage } from './features/planning/CareerPlanPage'
import { PlanningJobDetailPage } from './features/planning/PlanningJobDetailPage'

export function App() {
  return <AppProviders><Routes><Route element={<AppShell />}><Route index element={<ProfileGate><TodayPage /></ProfileGate>} /><Route path="plan" element={<ProfileGate><CareerPlanPage /></ProfileGate>} /><Route path="jobs/:jobId" element={<ProfileGate><PlanningJobDetailPage /></ProfileGate>} /><Route path="opportunities" element={<ProfileGate><OpportunitiesPage /></ProfileGate>} /><Route path="opportunities/:jobId" element={<ProfileGate><OpportunitiesPage /></ProfileGate>} /><Route path="updates" element={<ProfileGate><UpdatesPage /></ProfileGate>} /><Route path="profile" element={<ProfilePage />} /></Route></Routes></AppProviders>
}
