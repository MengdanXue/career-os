import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { AsyncState } from '../../components/AsyncState'
import { confirmCandidateFacts, getCandidateFacts, listCandidates, profileKeys, updateCandidate } from './profileApi'
import type { CandidateProfile, CandidateProfileFacts, CandidateProfileUpdate } from './profileSchema'
import { ProfileForm } from './ProfileForm'

const educationLabels: Record<string, string> = { HIGH_SCHOOL: '高中', ASSOCIATE: '专科', BACHELOR: '本科', MASTER: '硕士', DOCTORATE: '博士' }

function EducationSummary({ candidate }: { candidate: CandidateProfile }) {
  return <div className="education-summary" aria-label="教育经历">
    {(candidate.educationRecords ?? []).map((record, index) => <p key={`${record.educationLevel}-${index}`}>
      {[record.institutionName, educationLabels[record.educationLevel] ?? record.educationLevel, record.majorName, record.graduationYear, record.completionStatus === 'COMPLETED' ? '已毕业' : '预计毕业'].filter(value => value !== null && value !== '').join(' · ')}
    </p>)}
  </div>
}

function Readiness({ facts }: { facts: CandidateProfileFacts }) {
  return <div className="fact-readiness" aria-label="资料确认状态">
    <span className="fact-count fact-confirmed">已确认 {facts.confirmedCount}</span>
    <span className="fact-count fact-pending">待确认 {facts.unconfirmedCount}</span>
    <span className="fact-count fact-unknown">未知 {facts.unknownCount}</span>
  </div>
}

export function ProfilePage() {
  const client = useQueryClient()
  const candidates = useQuery({ queryKey: profileKeys.list, queryFn: listCandidates })
  const selectedCandidateId = localStorage.getItem('career-os.selected-candidate')
  const listedCandidate = candidates.data?.find(item => item.id === selectedCandidateId) ?? candidates.data?.[0] ?? null
  const candidateFacts = useQuery({
    queryKey: profileKeys.facts(listedCandidate?.id ?? 'pending'),
    queryFn: () => getCandidateFacts(listedCandidate!.id),
    enabled: Boolean(listedCandidate),
  })
  const [savedCandidate, setSavedCandidate] = useState<CandidateProfile | null>(null)
  const [savedFacts, setSavedFacts] = useState<CandidateProfileFacts | null>(null)
  const candidate = savedFacts?.profile ?? savedCandidate ?? candidateFacts.data?.profile ?? listedCandidate
  const facts = savedFacts ?? candidateFacts.data ?? null
  const isConfirmed = facts?.decisionReady === true
  const [editing, setEditing] = useState(false)

  const save = useMutation({
    mutationFn: async (update: CandidateProfileUpdate) => {
      const updated = await updateCandidate(candidate!.id, update)
      setSavedCandidate(updated)
      return confirmCandidateFacts(updated.id)
    },
    onSuccess: async snapshot => {
      setSavedCandidate(snapshot.profile)
      setSavedFacts(snapshot)
      localStorage.setItem('career-os.selected-candidate', snapshot.profile.id)
      setEditing(false)
      client.setQueryData<CandidateProfileFacts>(profileKeys.facts(snapshot.profile.id), snapshot)
      client.setQueryData<CandidateProfile[]>(profileKeys.list, current => current?.map(item => item.id === snapshot.profile.id ? snapshot.profile : item) ?? [snapshot.profile])
      await client.invalidateQueries({ predicate: query => query.queryKey[0] === 'decisions' || query.queryKey[0] === 'workbench' })
    },
    onError: async () => {
      if (!candidate) return
      await Promise.all([
        client.invalidateQueries({ queryKey: profileKeys.list }),
        client.invalidateQueries({ queryKey: profileKeys.facts(candidate.id) }),
      ])
    },
  })

  function beginEdit() {
    save.reset()
    setEditing(true)
  }

  const loading = candidates.isLoading || (Boolean(listedCandidate) && candidateFacts.isLoading)
  const error = candidates.error ?? candidateFacts.error

  return (
    <main className="profile-page page-frame">
      <AsyncState loading={loading} error={error} empty={candidates.isSuccess && !listedCandidate}>
        {candidate && facts && (!isConfirmed || editing || save.isPending || save.isError) ? <>
          <p className="eyebrow">PROFILE EVIDENCE · 决策资料</p>
          <h1>{isConfirmed ? '修改你的决策资料' : '先确认你的决策资料'}</h1>
          <p className="page-intro">只有你确认过的当前事实才会参与资格和匹配判断。修改字段后，旧确认会自动失效。</p>
          <Readiness facts={facts} />
          <ProfileForm candidate={candidate} submitLabel={isConfirmed ? '保存并重新确认' : '确认并开始'} pending={save.isPending} error={save.error} onSubmit={value => save.mutate(value)} />
        </> : candidate && facts ? <section className="profile-confirmed">
          <p className="eyebrow">PROFILE READY · 可用于决策</p>
          <h1>资料已确认</h1>
          <Readiness facts={facts} />
          <p>{candidate.displayName} · {educationLabels[candidate.highestEducation] ?? candidate.highestEducation} · {candidate.majors.join('、')}</p>
          <EducationSummary candidate={candidate} />
          <dl><div><dt>目标地点</dt><dd>{candidate.preferredLocations.join('、') || '明确不限'}</dd></div><div><dt>技能证据</dt><dd>{candidate.skills.join('、') || '明确未填写'}</dd></div><div><dt>资料版本</dt><dd>{candidate.profileVersion}</dd></div></dl>
          <button className="secondary-action" type="button" onClick={beginEdit}>修改资料</button>
        </section> : null}
      </AsyncState>
    </main>
  )
}
