import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiProblem, queryKeys } from '../../api/http'
import { applyReviewAction, type ReviewItem, type ReviewProposal } from './updateApi'

export function ReviewProposalForm({ review }: { review: ReviewItem }) {
  const client = useQueryClient()
  const [title, setTitle] = useState(review.proposal.recruitmentEvent.title)
  const [note, setNote] = useState('')
  const action = useMutation({
    mutationFn: ({ decision, correctedPayload }: { decision: string; correctedPayload: ReviewProposal | null }) => applyReviewAction(review.id, { decision, expectedVersion: review.version, correctedPayload, note: note || null }),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: queryKeys.reviews('PENDING') }) },
    onError: async error => { if (error instanceof ApiProblem && error.code === 'REVIEW_CONFLICT') await client.invalidateQueries({ queryKey: queryKeys.reviews('PENDING') }) },
  })
  const corrected = (): ReviewProposal => ({ ...review.proposal, recruitmentEvent: { ...review.proposal.recruitmentEvent, title } })
  const conflict = action.error instanceof ApiProblem && action.error.code === 'REVIEW_CONFLICT'
  return <div className="review-actions"><label>公告标题修正<input aria-label="公告标题修正" value={title} onChange={event => setTitle(event.target.value)} /></label><label>复核说明<textarea value={note} onChange={event => setNote(event.target.value)} rows={2} /></label>{conflict && <p className="form-error" role="alert">复核已被其他操作更新，请核对新版本后再次提交。</p>}{action.isError && !conflict && <p className="form-error" role="alert">复核操作没有完成，请检查修正内容。</p>}<div><button type="button" disabled={action.isPending} onClick={() => action.mutate({ decision: 'CONFIRM', correctedPayload: null })}>确认并入库</button><button type="button" disabled={action.isPending} onClick={() => action.mutate({ decision: 'CORRECT', correctedPayload: corrected() })}>修正并入库</button><button type="button" disabled={action.isPending} onClick={() => action.mutate({ decision: 'NEED_MORE_EVIDENCE', correctedPayload: null })}>需要更多证据</button><button className="reject" type="button" disabled={action.isPending} onClick={() => action.mutate({ decision: 'REJECT', correctedPayload: null })}>拒绝此结果</button></div></div>
}
