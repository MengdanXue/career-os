import type { AssessmentDimension } from './opportunityApi'

const dimensionLabels: Record<string, string> = {
  MAJOR_FIT: '专业匹配', SKILL_FIT: '技能匹配', RESEARCH_FIT: '研究 / 业务方向',
  EMPLOYMENT_SECURITY: '用工保障', FUNDING_STABILITY: '经费稳定', ORGANIZATION_STABILITY: '单位稳定',
}

export function DimensionList({ dimensions }: { dimensions: AssessmentDimension[] }) {
  return <ul className="dimension-list">{dimensions.map(dimension => {
    const unknown = dimension.factStatus === 'UNKNOWN'
    return <li key={`${dimension.type}-${dimension.reasonCode}`}><div><strong>{dimensionLabels[dimension.type] ?? dimension.type}</strong><span className={unknown ? 'unknown' : ''}>{unknown ? '待核实' : `${dimension.achievedPoints}/${dimension.maximumPoints}`}</span></div><p>{dimension.explanation}</p>{dimension.evidenceIds.length > 0 ? <small>证据 {dimension.evidenceIds.map(id => id.slice(0, 8)).join('、')}</small> : <small>尚无可引用证据</small>}</li>
  })}</ul>
}
