import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { AsyncState } from '../../components/AsyncState'
import type { CandidateEvidenceTask, CandidateEvidenceTasks } from '../personal/personalTypes'
import { confirmCandidateFacts, getCandidateEvidenceTasks, getCandidateFacts, listCandidates, profileKeys, recomputeDecisionChanges, updateCandidate } from './profileApi'
import type { DecisionChangeSummary } from './decisionChangeTypes'
import type { CandidateProfile, CandidateProfileFacts, CandidateProfileUpdate } from './profileSchema'
import { ProfileForm } from './ProfileForm'

const educationLabels: Record<string, string> = { HIGH_SCHOOL: '高中', ASSOCIATE: '专科', BACHELOR: '本科', MASTER: '硕士', DOCTORATE: '博士' }
const politicalLabels: Record<string, string> = { CPC_MEMBER: '中共党员', CPC_PROBATIONARY: '中共预备党员', NON_MEMBER: '非中共党员', UNKNOWN: '待确认' }

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

function EvidenceTaskPanel({ data, onSelect }: { data: CandidateEvidenceTasks; onSelect: (task: CandidateEvidenceTask) => void }) {
  if (data.items.length === 0 && data.available) return null
  return <section className="profile-evidence-priorities" aria-label="资格证据任务">
    <header><div><p className="eyebrow">QUALIFICATION GAPS</p><h2>最影响资格的缺口</h2></div><p>这里只列需要你本人补充或确认的事实，按影响岗位数量排序。</p></header>
    {!data.available && data.message && <p className="profile-task-warning" role="status">{data.message}</p>}
    <div>{data.items.slice(0, 3).map(task => <article key={task.code}>
      <span>{task.affectedJobCount > 0 ? `影响 ${task.affectedJobCount} 个岗位` : '影响范围待更新'}</span>
      <h3>{task.title}</h3><p>{task.reason}</p>
      <button type="button" onClick={() => onSelect(task)}>处理这项</button>
    </article>)}</div>
  </section>
}

function employmentSummary(candidate: CandidateProfile) {
  const verified = candidate.employmentRecords.filter(record => record.verificationStatus === 'VERIFIED')
  if (verified.length > 0) return `${verified.length} 段经历已核验；年限按起止日期合并计算`
  if (candidate.experienceYears !== null) return `旧资料记录 ${candidate.experienceYears} 年；硬资格仍需逐段核验`
  return '尚未核验'
}

function politicalSummary(candidate: CandidateProfile, facts: CandidateProfileFacts) {
  if (facts.statuses.POLITICAL_AFFILIATION === 'CONFIRMED') return politicalLabels[candidate.politicalAffiliation]
  return candidate.politicalAffiliation === 'UNKNOWN'
    ? '待确认'
    : `待确认（旧值：${politicalLabels[candidate.politicalAffiliation]}）`
}

function skillSummary(candidate: CandidateProfile, facts: CandidateProfileFacts) {
  if (candidate.skills.length > 0) {
    const suffix = facts.statuses.SKILLS === 'CONFIRMED' ? '' : '（待确认）'
    return `${candidate.skills.join('、')}${suffix}`
  }
  return facts.statuses.SKILLS === 'CONFIRMED' ? '明确没有' : '尚未提供'
}

function localDate() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

function DecisionChangePanel({ summary }: { summary: DecisionChangeSummary }) {
  return <section className="decision-change-panel" aria-label="资料更新后的岗位变化">
    <header><p className="eyebrow">DECISION DIFF · 同批岗位</p><h2>本次资料更新带来的变化</h2></header>
    {!summary.available ? <p className="decision-change-unavailable">{summary.message}</p> : <>
      <div className="decision-change-counts">
        <strong>新增可报 {summary.newlyEligibleCount}</strong>
        <strong>减少待确认 {summary.resolvedUncertaintyCount}</strong>
        <strong>新增不可报 {summary.newlyIneligibleCount}</strong>
      </div>
      {summary.affectedJobs.length === 0 ? <p>同一批岗位的资格结论没有变化。</p> : <div className="decision-change-jobs">
        {summary.affectedJobs.map(job => <article key={job.jobId}>
          <h3><Link to={job.deepLink}>{job.title} · {job.organizationName}</Link></h3>
          {job.reasons.map(reason => <p key={reason}>{reason}</p>)}
        </article>)}
      </div>}
    </>}
  </section>
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
  const evidenceTasks = useQuery({
    queryKey: profileKeys.evidenceTasks(listedCandidate?.id ?? 'pending'),
    queryFn: () => getCandidateEvidenceTasks(listedCandidate!.id),
    enabled: Boolean(listedCandidate),
  })
  const [savedCandidate, setSavedCandidate] = useState<CandidateProfile | null>(null)
  const [savedFacts, setSavedFacts] = useState<CandidateProfileFacts | null>(null)
  const candidate = savedFacts?.profile ?? savedCandidate ?? candidateFacts.data?.profile ?? listedCandidate
  const facts = savedFacts ?? candidateFacts.data ?? null
  const isConfirmed = facts?.decisionReady === true
  const [editing, setEditing] = useState(false)
  const [requestedAnchor, setRequestedAnchor] = useState<string | null>(null)
  const [decisionChange, setDecisionChange] = useState<DecisionChangeSummary | null>(null)
  const [decisionChangeError, setDecisionChangeError] = useState<Error | null>(null)
  const [decisionChangePending, setDecisionChangePending] = useState(false)
  const [changeRequest, setChangeRequest] = useState<{ candidateId: string; previousProfileVersion: string } | null>(null)

  async function refreshDecisionChanges(request: { candidateId: string; previousProfileVersion: string }) {
    setDecisionChangePending(true)
    setDecisionChangeError(null)
    setDecisionChange(null)
    try {
      setDecisionChange(await recomputeDecisionChanges(request.candidateId, request.previousProfileVersion, localDate()))
    } catch (error) {
      setDecisionChangeError(error instanceof Error ? error : new Error('岗位结论更新暂时失败'))
    } finally {
      setDecisionChangePending(false)
    }
  }

  useEffect(() => {
    if (!editing || !requestedAnchor) return
    const target = document.getElementById(requestedAnchor)
    if (!target) return
    if ('scrollIntoView' in target) target.scrollIntoView({ block: 'center' })
    const focusTarget = target.querySelector<HTMLElement>('input, select, button')
    focusTarget?.focus()
    setRequestedAnchor(null)
  }, [editing, requestedAnchor])

  const save = useMutation({
    mutationFn: async (update: CandidateProfileUpdate) => {
      const previousProfileVersion = candidate!.profileVersion
      const updated = await updateCandidate(candidate!.id, update)
      setSavedCandidate(updated)
      return { snapshot: await confirmCandidateFacts(updated.id), previousProfileVersion }
    },
    onSuccess: async ({ snapshot, previousProfileVersion }) => {
      setSavedCandidate(snapshot.profile)
      setSavedFacts(snapshot)
      localStorage.setItem('career-os.selected-candidate', snapshot.profile.id)
      setEditing(false)
      client.setQueryData<CandidateProfileFacts>(profileKeys.facts(snapshot.profile.id), snapshot)
      client.setQueryData<CandidateProfile[]>(profileKeys.list, current => current?.map(item => item.id === snapshot.profile.id ? snapshot.profile : item) ?? [snapshot.profile])
      const request = { candidateId: snapshot.profile.id, previousProfileVersion }
      setChangeRequest(request)
      await refreshDecisionChanges(request)
      await client.invalidateQueries({ predicate: query => query.queryKey[0] === 'decisions' || query.queryKey[0] === 'workbench' })
      await Promise.all([
        client.invalidateQueries({ queryKey: profileKeys.evidenceTasks(snapshot.profile.id) }),
        client.invalidateQueries({ predicate: query => query.queryKey[0] === 'personal-actions' || query.queryKey[0] === 'career-plan' }),
      ])
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
    setDecisionChange(null)
    setDecisionChangeError(null)
    setChangeRequest(null)
    setEditing(true)
  }

  function handleEvidenceTask(task: CandidateEvidenceTask) {
    save.reset()
    setRequestedAnchor(task.deepLink.split('#')[1] ?? null)
    setEditing(true)
  }

  const loading = candidates.isLoading || (Boolean(listedCandidate) && candidateFacts.isLoading)
  const error = candidates.error ?? candidateFacts.error

  return (
    <main className="profile-page page-frame">
      <AsyncState loading={loading} error={error} empty={candidates.isSuccess && !listedCandidate}>
        {candidate && facts ? <>
          {evidenceTasks.error && <p className="profile-task-warning" role="status">
            {evidenceTasks.error instanceof Error ? evidenceTasks.error.message : '证据任务暂时无法读取'}
          </p>}
          {evidenceTasks.data && <EvidenceTaskPanel data={evidenceTasks.data} onSelect={handleEvidenceTask} />}
          {isConfirmed && decisionChangePending && <p className="decision-change-progress" role="status">资料已保存，岗位结论正在更新…</p>}
          {isConfirmed && decisionChangeError && <section className="decision-change-error" role="alert">
            <strong>{decisionChangeError.message}</strong>
            <p>资料已经保存并确认。</p><p>旧岗位结论不会被标记为当前结果。</p>
            <button className="secondary-action" type="button" disabled={decisionChangePending || !changeRequest} onClick={() => changeRequest && refreshDecisionChanges(changeRequest)}>重新更新岗位结论</button>
          </section>}
          {isConfirmed && decisionChange && <DecisionChangePanel summary={decisionChange} />}
          {(!isConfirmed || editing || save.isPending || save.isError) ? <section className="profile-editing">
            <p className="eyebrow">PROFILE EVIDENCE · 决策资料</p>
            <h1>{isConfirmed ? '修改你的决策资料' : '先确认你的决策资料'}</h1>
            <p className="page-intro">只有你确认过的当前事实才会参与资格和匹配判断。修改字段后，旧确认会自动失效。</p>
            <Readiness facts={facts} />
            <ProfileForm candidate={candidate} submitLabel={isConfirmed ? '保存并重新确认' : '确认并开始'} pending={save.isPending} error={save.error} onSubmit={value => save.mutate(value)} />
          </section> : <section className="profile-confirmed">
            <p className="eyebrow">PROFILE READY · 可用于决策</p>
            <h1>资料已确认</h1>
            <Readiness facts={facts} />
            <p>{candidate.displayName} · {educationLabels[candidate.highestEducation] ?? candidate.highestEducation} · {candidate.majors.join('、')}</p>
            <EducationSummary candidate={candidate} />
            <dl>
              <div><dt>出生日期</dt><dd>{candidate.birthDate.year}-{String(candidate.birthDate.month).padStart(2, '0')}-{candidate.birthDate.day ? String(candidate.birthDate.day).padStart(2, '0') : '待确认'}</dd></div>
              <div><dt>目标地点</dt><dd>{candidate.preferredLocations.join('、') || '明确不限'}</dd></div>
              <div><dt>政治面貌</dt><dd>{politicalSummary(candidate, facts)}</dd></div>
              <div><dt>工作经历证据</dt><dd>{employmentSummary(candidate)}</dd></div>
              <div><dt>技能证据</dt><dd>{skillSummary(candidate, facts)}</dd></div>
              <div><dt>资料版本</dt><dd>{candidate.profileVersion}</dd></div>
            </dl>
            <button className="secondary-action" type="button" onClick={beginEdit}>修改资料</button>
          </section>}
        </> : null}
      </AsyncState>
    </main>
  )
}
