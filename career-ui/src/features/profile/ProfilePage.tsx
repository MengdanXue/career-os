import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { AsyncState } from '../../components/AsyncState'
import { listCandidates, profileKeys, updateCandidate } from './profileApi'
import { confirmationKey, newProfileVersion, type CandidateProfile, type CandidateProfileUpdate } from './profileSchema'
import { ProfileForm } from './ProfileForm'

export function ProfilePage() {
  const client = useQueryClient()
  const candidates = useQuery({ queryKey: profileKeys.list, queryFn: listCandidates })
  const [savedCandidate, setSavedCandidate] = useState<CandidateProfile | null>(null)
  const candidate = savedCandidate ?? candidates.data?.[0] ?? null
  const isConfirmed = candidate ? localStorage.getItem(confirmationKey(candidate.id)) === 'true' : false
  const [editing, setEditing] = useState(false)
  const [profileVersion, setProfileVersion] = useState(newProfileVersion)

  const save = useMutation({
    mutationFn: (update: CandidateProfileUpdate) => updateCandidate(candidate!.id, update),
    onSuccess: async updated => {
      setSavedCandidate(updated)
      localStorage.setItem('career-os.selected-candidate', updated.id)
      localStorage.setItem(confirmationKey(updated.id), 'true')
      setEditing(false)
      client.setQueryData<CandidateProfile[]>(profileKeys.list, current => current?.map(item => item.id === updated.id ? updated : item) ?? [updated])
      await client.invalidateQueries({ predicate: query => query.queryKey[0] === 'decisions' || query.queryKey[0] === 'workbench' })
    },
  })

  function beginEdit() {
    setProfileVersion(newProfileVersion())
    save.reset()
    setEditing(true)
  }

  return (
    <main className="profile-page page-frame">
      <AsyncState loading={candidates.isLoading} error={candidates.error} empty={candidates.isSuccess && !candidate}>
        {candidate && (!isConfirmed || editing || save.isPending || save.isError) ? <>
          <p className="eyebrow">PROFILE EVIDENCE · 决策资料</p>
          <h1>{isConfirmed ? '修改你的决策资料' : '先确认你的决策资料'}</h1>
          <p className="page-intro">这些事实会影响年龄、学历、专业和用工形式判断。请只确认真实情况，之后可以随时修改。</p>
          <ProfileForm candidate={candidate} profileVersion={profileVersion} submitLabel={isConfirmed ? '保存修改' : '确认并开始'} pending={save.isPending} error={save.error} onSubmit={value => save.mutate(value)} />
        </> : candidate ? <section className="profile-confirmed">
          <p className="eyebrow">PROFILE READY · 可用于决策</p>
          <h1>资料已确认</h1>
          <p>{candidate.displayName} · {candidate.highestEducation === 'MASTER' ? '硕士' : candidate.highestEducation} · {candidate.majors.join('、')}</p>
          <dl><div><dt>目标地点</dt><dd>{candidate.preferredLocations.join('、') || '待补充'}</dd></div><div><dt>技能证据</dt><dd>{candidate.skills.join('、') || '待补充'}</dd></div><div><dt>资料版本</dt><dd>{candidate.profileVersion}</dd></div></dl>
          <button className="secondary-action" type="button" onClick={beginEdit}>修改资料</button>
        </section> : null}
      </AsyncState>
    </main>
  )
}
