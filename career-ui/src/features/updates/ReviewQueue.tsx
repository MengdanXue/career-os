import type { ReviewItem } from './updateApi'

export function ReviewQueue({ reviews, selectedId, onSelect }: { reviews: ReviewItem[]; selectedId: string | null; onSelect: (id: string) => void }) {
  if (!reviews.length) return <div className="queue-empty"><strong>没有待复核数据</strong><p>新的公告解析结果会出现在这里。</p></div>
  return <div className="review-queue">{reviews.map(review => <article key={review.id} data-active={selectedId === review.id}><button type="button" aria-label={`查看复核 ${review.proposal.recruitmentEvent.title}`} onClick={() => onSelect(review.id)}><span>版本 {review.version} · 置信度 {Math.round(review.proposal.confidence * 100)}%</span><strong>{review.proposal.recruitmentEvent.title}</strong><small>{review.proposal.organization.name} · {review.proposal.jobs.length} 个岗位</small></button></article>)}</div>
}
