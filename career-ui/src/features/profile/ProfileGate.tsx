import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { AsyncState } from '../../components/AsyncState'
import { listCandidates, profileKeys } from './profileApi'
import { confirmationKey } from './profileSchema'
import { ProfilePage } from './ProfilePage'

export function ProfileGate({ children }: { children: ReactNode }) {
  const candidates = useQuery({ queryKey: profileKeys.list, queryFn: listCandidates })
  const candidate = candidates.data?.find(item => item.id === localStorage.getItem('career-os.selected-candidate')) ?? candidates.data?.[0]
  if (candidates.isLoading || candidates.isError) return <main className="page-frame"><AsyncState loading={candidates.isLoading} error={candidates.error}>{null}</AsyncState></main>
  if (!candidate || localStorage.getItem(confirmationKey(candidate.id)) !== 'true') return <ProfilePage />
  return <>{children}</>
}
