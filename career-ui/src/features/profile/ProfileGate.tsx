import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { AsyncState } from '../../components/AsyncState'
import { getCandidateFacts, listCandidates, profileKeys } from './profileApi'
import { ProfilePage } from './ProfilePage'

export function ProfileGate({ children }: { children: ReactNode }) {
  const candidates = useQuery({ queryKey: profileKeys.list, queryFn: listCandidates })
  const candidate = candidates.data?.find(item => item.id === localStorage.getItem('career-os.selected-candidate')) ?? candidates.data?.[0]
  const facts = useQuery({ queryKey: profileKeys.facts(candidate?.id ?? 'pending'), queryFn: () => getCandidateFacts(candidate!.id), enabled: Boolean(candidate) })
  const loading = candidates.isLoading || (Boolean(candidate) && facts.isLoading)
  const error = candidates.error ?? facts.error
  if (loading || error) return <main className="page-frame"><AsyncState loading={loading} error={error}>{null}</AsyncState></main>
  if (!candidate || !facts.data?.decisionReady) return <ProfilePage />
  return <>{children}</>
}
