import { Route, Routes } from 'react-router-dom'
import { AppProviders } from './app/AppProviders'
import { AppShell } from './app/AppShell'
import { ProfileGate } from './features/profile/ProfileGate'
import { ProfilePage } from './features/profile/ProfilePage'
import { OpportunitiesPage } from './features/opportunities/OpportunitiesPage'
import { TodayPage } from './features/today/TodayPage'

function RoutePlaceholder({ title }: { title: string }) {
  return <main className="shell-content"><p className="eyebrow">CAREER OS</p><h1>{title}</h1></main>
}

export function App() {
  return <AppProviders><Routes><Route element={<AppShell />}><Route index element={<ProfileGate><TodayPage /></ProfileGate>} /><Route path="opportunities" element={<ProfileGate><OpportunitiesPage /></ProfileGate>} /><Route path="opportunities/:jobId" element={<ProfileGate><OpportunitiesPage /></ProfileGate>} /><Route path="updates" element={<ProfileGate><RoutePlaceholder title="更新岗位库" /></ProfileGate>} /><Route path="profile" element={<ProfilePage />} /></Route></Routes></AppProviders>
}
