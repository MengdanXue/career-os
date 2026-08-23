import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AsyncState } from '../../components/AsyncState'
import { getCareerPlan, planningKeys, type Route, type Scenario } from './planningApi'

const evidenceLabels = { STRONG: '证据充分', MODERATE: '证据中等', LIMITED: '证据有限', INSUFFICIENT: '样本不足' }
const scenarioOrder = ['PRE_GRADUATION', 'DEGREE_PENDING_VERIFICATION', 'MASTER_VERIFIED']
const scenarioLabels: Record<string, string> = { PRE_GRADUATION: '本科阶段', DEGREE_PENDING_VERIFICATION: '硕士待认证', MASTER_VERIFIED: '硕士已认证' }

function ScenarioRail({ current, future }: { current: Scenario; future: Scenario[] }) {
  const scenarios = [current, ...future].sort((a, b) => scenarioOrder.indexOf(a.code) - scenarioOrder.indexOf(b.code))
  return <ol className="scenario-rail" aria-label="三种资格场景">
    {scenarios.map((scenario, index) => <li key={scenario.code} data-current={scenario.current}>
      <span>{index + 1}</span><div><small>{scenario.current ? '当前场景' : '未来场景'}</small><h3>{scenario.label}</h3><p>{scenario.description}</p>{scenario.effectiveFrom && <time dateTime={scenario.effectiveFrom}>{scenario.effectiveFrom} 起</time>}</div>
    </li>)}
  </ol>
}

function RouteCard({ route, rank, currentScenario, historyUnavailable }: { route: Route; rank: number; currentScenario: string; historyUnavailable: boolean }) {
  return <article className="route-card">
    <header><span className="route-rank">{historyUnavailable ? '排名暂停' : `路线 ${rank}`}</span><span className={`evidence-chip evidence-${route.evidenceStrength.toLowerCase()}`}>{evidenceLabels[route.evidenceStrength]}</span></header>
    <h2>{route.label}</h2>
    <div className="decision-index"><strong>{historyUnavailable ? '—' : route.priorityScore}</strong><span>{historyUnavailable ? '历史数据恢复后重新计算' : route.priorityLabel}</span></div>
    <dl className="route-counts"><div><dt>历史岗位</dt><dd>{historyUnavailable ? '—' : route.historicalJobCount}</dd></div><div><dt>独立招聘</dt><dd>{historyUnavailable ? '—' : route.eventCount}</dd></div><div><dt>正式用工</dt><dd>{historyUnavailable ? '—' : route.formalJobCount}</dd></div></dl>
    <div className="route-eligibility" aria-label="三阶段资格结果">
      <p><strong>三阶段逐岗判断</strong><span>历史岗位模拟，不等于今年可报名</span></p>
      {route.scenarioBreakdowns.map(value => <section key={value.scenarioCode} data-current={value.scenarioCode === currentScenario}>
        <h3>{scenarioLabels[value.scenarioCode] ?? value.scenarioCode}{value.scenarioCode === currentScenario && <small>当前</small>}</h3>
        <dl><div data-outcome="eligible"><dt>可报</dt><dd>{historyUnavailable ? '—' : value.eligible}</dd></div><div data-outcome="conditional"><dt>条件可报</dt><dd>{historyUnavailable ? '—' : value.conditionallyEligible}</dd></div><div data-outcome="uncertain"><dt>待确认</dt><dd>{historyUnavailable ? '—' : value.uncertain}</dd></div><div data-outcome="ineligible"><dt>不可报</dt><dd>{historyUnavailable ? '—' : value.ineligible}</dd></div></dl>
      </section>)}
    </div>
    {!historyUnavailable && <details className="route-score-detail"><summary>为什么是 {route.priorityScore} 分</summary><ol>{route.scoreComponents.map(component => <li key={component.code} data-backed={component.evidenceBacked}><div><strong>{component.label}</strong><span>权重 {component.weight}%</span></div><b>{component.score}</b><p>{component.basis}</p></li>)}</ol></details>}
    <div className="route-notes"><div><h3>你的优势</h3><ul>{route.advantages.map(value => <li key={value}>{value}</li>)}</ul></div><div><h3>准备重点</h3><ul>{route.preparationFocus.map(value => <li key={value}>{value}</li>)}</ul></div></div>
    {route.organizations.length > 0 && <p className="route-organizations"><b>历史涉及单位</b> {route.organizations.join('、')}</p>}
  </article>
}

export function CareerPlanPage() {
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const targetYear = new Date().getFullYear() + 1
  const plan = useQuery({ queryKey: planningKeys.plan(candidateId, targetYear), queryFn: () => getCareerPlan(candidateId, targetYear) })
  const data = plan.data
  const keyAgeWindow = data?.ageWindows.find(window => window.year === data.targetYear + 1 && window.maximumAge === 35) ?? data?.ageWindows.find(window => window.eligible)
  const primaryRoute = data?.recommendedRoutes[0]
  const historyUnavailable = data?.dataCoverage.failedSections?.includes('HISTORY') ?? false

  return <main className="planning-page page-frame wide-frame">
    <AsyncState loading={plan.isLoading && !data} error={data ? undefined : plan.error}>
      {data && <>
        {plan.error && <aside className="stale-plan-banner" role="status"><strong>本次刷新失败，正在显示上次成功结果</strong><span>最后成功更新：{new Date(plan.dataUpdatedAt).toLocaleString('zh-CN')}</span></aside>}
        <header className="planning-hero">
          <div><p className="eyebrow">CAREER ROUTE · {data.targetYear} 规划</p><h1>我的半体制规划</h1><p className="planning-current">当前：{data.candidateSnapshot.educationSummary}</p></div>
          <div className="plan-stamp"><span>数据截至</span><time dateTime={data.dataCoverage.loadedAt}>{new Date(data.dataCoverage.loadedAt).toLocaleDateString('zh-CN')}</time><small>{data.algorithmVersion}</small></div>
        </header>

        <section className="plan-conclusion" aria-labelledby="plan-conclusion-title">
          <p className="section-number">先看结论</p><h2 id="plan-conclusion-title">{historyUnavailable ? '历史数据本次未读到，暂不生成主攻路线' : `主攻 ${primaryRoute?.label ?? '事业单位技术岗'}`}</h2>
          <p>{historyUnavailable ? '画像、年龄窗口与材料动作仍可查看；路线排名与历史数量等待数据恢复后重新计算。' : `${keyAgeWindow?.label ?? '年龄窗口将在历史岗位规则补齐后显示'}；${data.recruitmentWindows[0]?.label ?? '持续关注官方公告'}。先把工作经历证据和留服认证路径准备好。`}</p>
          <div><a href="#routes">比较四条路线</a><a href="#actions">查看行动时间线</a></div>
        </section>

        {!data.dataCoverage.complete && <aside className="coverage-banner" role="status"><strong>覆盖提醒</strong><p>{data.dataCoverage.warnings.join(' ')}</p><span>{data.dataCoverage.completeSourceYearCount}/{data.dataCoverage.sourceYearCount} 个来源年度已完成</span></aside>}

        <section className="plan-section scenario-section"><header><p className="section-number">资格不是一个静态标签</p><h2>同一份画像，分三种场景判断</h2></header><ScenarioRail current={data.currentScenario} future={data.futureScenarios} /></section>

        <section className="plan-section history-section"><header><p className="section-number">2024—2026 官方历史切片</p><h2>历史供给是参考规模，不是可报数量</h2></header>
          <div className="history-ledger" role="table" aria-label="年度历史岗位">
            <div className="history-row history-head" role="row"><span>年度</span><span>岗位行</span><span>独立招聘</span><span>正式用工</span><span>覆盖</span></div>
            {data.historicalSummary.map(summary => <div className="history-row" role="row" key={summary.year}><strong>{summary.year}</strong><span>{historyUnavailable ? '—' : summary.jobCount}</span><span>{historyUnavailable ? '—' : summary.eventCount}</span><span>{historyUnavailable ? '—' : summary.formalJobCount}</span><span>{historyUnavailable ? '读取失败' : summary.coverageComplete ? '已完成' : '待补齐'}</span></div>)}
          </div>
        </section>

        <section className="plan-section" id="routes"><header><p className="section-number">路线比较</p><h2>{historyUnavailable ? '历史数据读取失败，路线排名已暂停' : '按资格准备度、经历复用和稳定性排序'}</h2></header><div className="route-grid">{data.recommendedRoutes.map((route, index) => <RouteCard route={route} rank={index + 1} currentScenario={data.currentScenario.code} historyUnavailable={historyUnavailable} key={route.code} />)}</div></section>

        <div className="planning-split">
          <section className="plan-section age-section"><header><p className="section-number">年龄窗口</p><h2>按完整生日计算</h2></header><ul>{data.ageWindows.filter(window => window.maximumAge === 35 || (window.year === 2031 && window.maximumAge === 38)).map(window => <li key={`${window.year}-${window.maximumAge}`} data-eligible={window.eligible}><time dateTime={window.referenceDate}>{window.year}</time><div><strong>{window.label}</strong><small>参考日 {window.referenceDate} · 实龄 {window.candidateAge}</small></div></li>)}</ul><p className="conditional-note">如果未来政策与参考日保持不变；正式公告发布后以公告为准。</p></section>
          <section className="plan-section calendar-section"><header><p className="section-number">招聘与考试节奏</p><h2>什么时候开始准备</h2></header><div className="month-strip">{data.recruitmentWindows.map(window => <div key={window.month}><strong>{window.month}月</strong><span>{window.eventCount} 个历史事件</span><small>预计关注窗口</small></div>)}</div><h3>历史笔试科目</h3><ul>{data.examPatterns.length ? data.examPatterns.map(pattern => <li key={pattern.subject}>{pattern.subject}<span>{pattern.eventCount} 个事件出现</span></li>) : <li>历史科目尚未补齐</li>}</ul></section>
        </div>

        <section className="plan-section risk-section"><header><p className="section-number">资格风险</p><h2>先解决会改变“能不能报”的事项</h2></header><div>{data.qualificationRisks.map(risk => <article key={risk.code} data-severity={risk.severity}><span>{risk.severity === 'HIGH' ? '优先处理' : '需要确认'}</span><h3>{risk.title}</h3><p>{risk.detail}</p></article>)}</div></section>

        <section className="plan-section action-section" id="actions"><header><p className="section-number">从现在到第二个完整周期</p><h2>行动时间线</h2></header><ol>{data.actionTimeline.map((action, index) => <li key={`${action.startsOn}-${action.title}`} data-now={action.status === 'NOW'}><span>{String(index + 1).padStart(2, '0')}</span><div><time dateTime={action.startsOn}>{action.startsOn} — {action.endsOn}</time><h3>{action.title}</h3><p>{action.detail}</p></div></li>)}</ol></section>

        <section className="plan-section evidence-section"><header><p className="section-number">官方依据</p><h2>代表岗位可下钻查看完整详情</h2></header><div className="evidence-list">{data.recommendedRoutes.flatMap(route => route.representativeJobs.map(job => ({ ...job, route: route.label }))).map(job => <article key={job.jobId}><span>{job.year} · {job.route}</span><h3><Link to={`/jobs/${job.jobId}`}>{job.title} · {job.organizationName}</Link></h3><a href={job.sourceUrl} target="_blank" rel="noreferrer">打开官方来源 ↗</a></article>)}</div></section>
      </>}
    </AsyncState>
  </main>
}
