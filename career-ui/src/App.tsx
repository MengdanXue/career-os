import { Route, Routes } from 'react-router-dom'
import { AppProviders } from './app/AppProviders'
import { AppShell } from './app/AppShell'
import { ProfileGate } from './features/profile/ProfileGate'
import { ProfilePage } from './features/profile/ProfilePage'

function TodayPlaceholder() {
  return <main className="shell-content"><p className="eyebrow">DAILY BRIEF · 今日简报</p><h1>Career OS</h1><p className="lead">把杭州及浙江半体制技术岗位，变成有证据、可复核的职业选择。</p><section className="empty-brief" aria-labelledby="brief-title"><div><p className="section-number">01</p><h2 id="brief-title">工作台正在接入</h2></div><p>下一步将连接你的候选人资料、岗位分层与每日变化。</p></section></main>
}

function RoutePlaceholder({ title }: { title: string }) {
  return <main className="shell-content"><p className="eyebrow">CAREER OS</p><h1>{title}</h1></main>
}

export function App() {
  return <AppProviders><Routes><Route element={<AppShell />}><Route index element={<ProfileGate><TodayPlaceholder /></ProfileGate>} /><Route path="opportunities" element={<ProfileGate><RoutePlaceholder title="机会池" /></ProfileGate>} /><Route path="opportunities/:jobId" element={<ProfileGate><RoutePlaceholder title="岗位档案" /></ProfileGate>} /><Route path="updates" element={<ProfileGate><RoutePlaceholder title="更新岗位库" /></ProfileGate>} /><Route path="profile" element={<ProfilePage />} /></Route></Routes></AppProviders>
}
