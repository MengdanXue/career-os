import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AsyncState } from '../../components/AsyncState'
import { getCareerPlan, planningKeys, type ExamSummary, type Route, type Scenario } from './planningApi'

const evidenceLabels = { STRONG: '证据充分', MODERATE: '证据中等', LIMITED: '证据有限', INSUFFICIENT: '覆盖不足' }
const scenarioOrder = ['MASTER_IN_PROGRESS', 'PRE_GRADUATION', 'DEGREE_PENDING_VERIFICATION', 'MASTER_VERIFIED']
const scenarioLabels: Record<string, string> = {
  MASTER_IN_PROGRESS: '境外硕士在读', PRE_GRADUATION: '本科阶段（兼容值）',
  DEGREE_PENDING_VERIFICATION: '硕士待认证', MASTER_VERIFIED: '硕士已认证',
}
const routeLabels: Record<string, string> = {
  PUBLIC_TECH: '事业单位信息技术岗', UNIVERSITY_HOSPITAL_IT: '高校与医院信息化岗',
  RESEARCH_SUPPORT: '科研与技术支撑岗', GOVERNMENT_SOE_DIGITAL: '政府国企数字化岗',
}
const processLabels: Record<string, string> = { NOTICE: '公告', APPLICATION_START: '报名开始', WRITTEN_EXAM: '笔试', INTERVIEW: '面试' }

function ScenarioRail({ current, future }: { current: Scenario; future: Scenario[] }) {
  const scenarios = [current, ...future].sort((a, b) => scenarioOrder.indexOf(a.code) - scenarioOrder.indexOf(b.code))
  return <ol className="scenario-rail" aria-label="三种资格场景">
    {scenarios.map((scenario, index) => <li key={scenario.code} data-current={scenario.current}>
      <span>{index + 1}</span><div><small>{scenario.current ? '当前场景' : '未来场景'}</small><h3>{scenario.label}</h3><p>{scenario.description}</p>{scenario.effectiveFrom && <time dateTime={scenario.effectiveFrom}>{scenario.effectiveFrom} 起</time>}</div>
    </li>)}
  </ol>
}

type ExamStageCounts = {
  confirmed: number; notRequired: number; notPublished: number; notCollected: number
  parseFailed: number; reviewRequired: number; unknown: number
}

function ExamStage({ title, total, counts }: { title: string; total: number; counts: ExamStageCounts }) {
  return <article className="exam-stage">
    <header><h3>{title}</h3><span>分母 {total} 个独立招聘事件</span></header>
    <dl>
      <div><dt>已明确安排</dt><dd>{counts.confirmed}</dd></div>
      <div><dt>明确不要求</dt><dd>{counts.notRequired}</dd></div>
      <div><dt>尚未发布</dt><dd>{counts.notPublished}</dd></div>
      <div><dt>尚未采集</dt><dd>{counts.notCollected}</dd></div>
      <div><dt>解析失败</dt><dd>{counts.parseFailed}</dd></div>
      <div><dt>等待复核</dt><dd>{counts.reviewRequired}</dd></div>
      <div><dt>无法判断</dt><dd>{counts.unknown}</dd></div>
    </dl>
  </article>
}

function ExamEvidence({ summary }: { summary: ExamSummary }) {
  return <>
    <div className="exam-stage-grid">
      <ExamStage title="笔试" total={summary.totalEvents} counts={{ confirmed: summary.writtenExamConfirmed, notRequired: summary.writtenExamNotRequired, notPublished: summary.writtenExamNotPublished, notCollected: summary.writtenExamNotCollected, parseFailed: summary.writtenExamParseFailed, reviewRequired: summary.writtenExamReviewRequired, unknown: summary.writtenExamUnknown }} />
      <ExamStage title="专业测试" total={summary.totalEvents} counts={{ confirmed: summary.professionalTestConfirmed, notRequired: summary.professionalTestNotRequired, notPublished: summary.professionalTestNotPublished, notCollected: summary.professionalTestNotCollected, parseFailed: summary.professionalTestParseFailed, reviewRequired: summary.professionalTestReviewRequired, unknown: summary.professionalTestUnknown }} />
      <ExamStage title="面试" total={summary.totalEvents} counts={{ confirmed: summary.interviewConfirmed, notRequired: summary.interviewNotRequired, notPublished: summary.interviewNotPublished, notCollected: summary.interviewNotCollected, parseFailed: summary.interviewParseFailed, reviewRequired: summary.interviewReviewRequired, unknown: summary.interviewUnknown }} />
    </div>
    <details className="evidence-state-legend" open><summary>这些状态分别是什么意思</summary><dl>
      <div><dt>NOT_PUBLISHED</dt><dd>官网说明另行通知/尚未发布</dd></div>
      <div><dt>NOT_COLLECTED</dt><dd>系统尚未采集该通知</dd></div>
      <div><dt>PARSE_FAILED</dt><dd>已取得官网原件，但自动解析失败</dd></div>
      <div><dt>REVIEW_REQUIRED</dt><dd>官网规则存在歧义，等待复核</dd></div>
      <div><dt>UNKNOWN</dt><dd>当前证据无法判断</dd></div>
    </dl></details>
  </>
}

function RouteCard({ route, rank, currentScenario, historyUnavailable }: { route: Route; rank: number | null; currentScenario: string; historyUnavailable: boolean }) {
  const ranked = !historyUnavailable && rank !== null && route.priorityScore !== null
  const unavailable = historyUnavailable || route.rankingState === 'DATA_FAILURE'
  const status = unavailable ? '历史数据读取失败，暂不计算' : route.rankingReason
  return <article className="route-card" data-ranking-state={route.rankingState}>
    <header><span className="route-rank">{ranked ? `路线 ${rank}` : unavailable ? '数据不可用' : '未参与排名'}</span><span className={`evidence-chip evidence-${route.evidenceStrength.toLowerCase()}`}>{evidenceLabels[route.evidenceStrength]}</span></header>
    <h2>{route.label}</h2>
    <p className="route-ranking-reason">{status}</p>
    <div className="decision-index"><strong>{ranked ? route.priorityScore : '—'}</strong><span>{ranked ? route.priorityLabel : '没有足够覆盖时不显示伪 0 分'}</span></div>
    <dl className="route-counts"><div><dt>已采集历史岗位</dt><dd>{historyUnavailable ? '—' : route.historicalJobCount}</dd></div><div><dt>独立招聘</dt><dd>{historyUnavailable ? '—' : route.eventCount}</dd></div><div><dt>正式用工</dt><dd>{historyUnavailable ? '—' : route.formalJobCount}</dd></div></dl>
    {route.scenarioBreakdowns.length > 0 && <div className="route-eligibility" aria-label="三阶段资格结果">
      <p><strong>逐岗资格判断</strong><span>历史实际与 {new Date().getFullYear() + 1} 类比结论分开保存</span></p>
      {route.scenarioBreakdowns.map(value => <section key={value.scenarioCode} data-current={value.scenarioCode === currentScenario}>
        <h3>{scenarioLabels[value.scenarioCode] ?? value.scenarioCode}{value.scenarioCode === currentScenario && <small>当前</small>}</h3>
        <dl><div data-outcome="eligible"><dt>可报</dt><dd>{historyUnavailable ? '—' : value.eligible}</dd></div><div data-outcome="conditional"><dt>条件可报</dt><dd>{historyUnavailable ? '—' : value.conditionallyEligible}</dd></div><div data-outcome="uncertain"><dt>待确认</dt><dd>{historyUnavailable ? '—' : value.uncertain}</dd></div><div data-outcome="ineligible"><dt>不可报</dt><dd>{historyUnavailable ? '—' : value.ineligible}</dd></div></dl>
      </section>)}
    </div>}
    {ranked && <details className="route-score-detail"><summary>为什么是 {route.priorityScore} 分</summary><ol>{route.scoreComponents.map(component => <li key={component.code} data-backed={component.evidenceBacked}><div><strong>{component.label}</strong><span>权重 {component.weight}%</span></div><b>{component.score}</b><p>{component.basis}</p></li>)}</ol></details>}
    <div className="route-notes"><div><h3>你的优势</h3>{route.advantages.length ? <ul>{route.advantages.map(value => <li key={value}>{value}</li>)}</ul> : <p>等待更多岗位证据</p>}</div><div><h3>准备重点</h3><ul>{route.preparationFocus.map(value => <li key={value}>{value}</li>)}</ul></div></div>
    {route.organizations.length > 0 && <p className="route-organizations"><b>历史涉及单位</b> {route.organizations.join('、')}</p>}
  </article>
}

export function CareerPlanPage() {
  const candidateId = localStorage.getItem('career-os.selected-candidate') ?? '01992f09-0000-7000-8000-000000000001'
  const targetYear = new Date().getFullYear() + 1
  const plan = useQuery({ queryKey: planningKeys.plan(candidateId, targetYear), queryFn: () => getCareerPlan(candidateId, targetYear) })
  const data = plan.data
  const keyAgeWindow = data?.ageWindows.find(window => window.year === data.targetYear + 1 && window.maximumAge === 35) ?? data?.ageWindows.find(window => window.eligible)
  const rankedRoutes = data?.recommendedRoutes.filter(route => (route.rankingState === 'RANKED' || route.rankingState === 'LIMITED') && route.priorityScore !== null) ?? []
  const primaryRoute = rankedRoutes[0]
  const historyUnavailable = data?.dataCoverage.failedSections?.includes('HISTORY') ?? false

  return <main className="planning-page page-frame wide-frame">
    <AsyncState loading={plan.isLoading && !data} error={data ? undefined : plan.error}>
      {data && <>
        {plan.error && <aside className="stale-plan-banner" role="status"><strong>本次刷新失败，正在显示上次成功结果</strong><span>最后成功更新：{new Date(plan.dataUpdatedAt).toLocaleString('zh-CN')}</span></aside>}
        <header className="planning-hero">
          <div><p className="eyebrow">CAREER ROUTE · {data.targetYear} 规划</p><h1>我的半体制规划</h1><p className="planning-current">当前：{data.candidateSnapshot.educationSummary}</p></div>
          <div className="plan-stamp"><span>分析证据截至</span><time dateTime={data.analysisCoverage.loadedAt}>{new Date(data.analysisCoverage.loadedAt).toLocaleDateString('zh-CN')}</time><small>{data.algorithmVersion}</small></div>
        </header>

        <section className="plan-conclusion" aria-labelledby="plan-conclusion-title">
          <p className="section-number">先看结论</p><h2 id="plan-conclusion-title">{data.graduateTrack.label}</h2>
          <p>{data.graduateTrack.detail}</p>
          <p className="primary-route-copy">{historyUnavailable ? '历史数据本次未读到，暂不生成主攻路线。' : primaryRoute ? `当前可比较路线中，优先准备：${primaryRoute.label}。` : '目标路线覆盖不足，暂不生成虚假排名。'}</p>
          <div><a href="#channels">看两条报考通道</a><a href="#actions">查看行动时间线</a></div>
        </section>

        <section className="plan-section channel-section" id="channels"><header><p className="section-number">不是只走一条路</p><h2>应届与社会人员通道并行准备</h2></header><div className="channel-grid">
          <article data-channel="graduate"><span>01 · TARGET YEAR</span><h3>应届通道</h3><strong>{data.graduateTrack.label}</strong><p>{data.graduateTrack.detail}</p><ul><li>优先核对当届、近届和“未落实工作单位”口径</li><li>逐岗核对学位取得与留服认证截止时点</li><li>历史公告实际不可报，不代表 {data.targetYear} 同类规则不可报</li></ul></article>
          <article data-channel="social"><span>02 · OPEN MARKET</span><h3>社会人员通道</h3><strong>本科已完成，社会招聘技术岗持续可评估</strong><p>不依赖应届身份；年龄、专业、职称和真实工作经历按公告逐项判断。</p><ul><li>工作经历只计算有起止日期和证明的记录</li><li>中级职称作为岗位条件或加分证据，不替代公告学历要求</li><li>与应届通道共用技术准备和官方来源监控</li></ul></article>
        </div></section>

        <section className="plan-section timing-section"><header><p className="section-number">关键时间与考试</p><h2>什么时候关注、考什么、哪些信息还没拿到</h2></header>
          <div className="process-window-grid">{data.processWindows.map(window => <article key={`${window.stage}-${window.month}`}><span>{processLabels[window.stage] ?? window.stage}</span><strong>{window.month} 月</strong><small>{window.eventCount} 个历史事件</small></article>)}</div>
          {data.examSummary.applicationToWrittenExamSamples > 0 && <p className="exam-interval">报名至笔试：{data.examSummary.averageApplicationToWrittenExamDays ?? '—'} 天平均值，基于 {data.examSummary.applicationToWrittenExamSamples} 个有完整日期的事件。</p>}
          <ExamEvidence summary={data.examSummary} />
        </section>

        <section className="plan-section" id="routes"><header><p className="section-number">路线比较</p><h2>{historyUnavailable ? '历史数据读取失败，路线排名已暂停' : '只给有覆盖依据的路线排序'}</h2>{historyUnavailable && <p className="route-pause-tag">排名暂停</p>}</header><div className="route-grid">{data.recommendedRoutes.map(route => {
          const rank = rankedRoutes.findIndex(value => value.code === route.code)
          return <RouteCard route={route} rank={rank < 0 ? null : rank + 1} currentScenario={data.currentScenario.code} historyUnavailable={historyUnavailable} key={route.code} />
        })}</div></section>

        <section className="plan-section market-coverage-section"><header><p className="section-number">目标市场覆盖</p><h2>登记来源不等于已经采集</h2></header>
          <div className="market-coverage-summary"><strong>已接入 {data.targetMarketCoverage.connected} / {data.targetMarketCoverage.targetCount}</strong><span>部分接入 {data.targetMarketCoverage.partial}</span><span>访问失败 {data.targetMarketCoverage.failed}</span><span>未接入 {data.targetMarketCoverage.notConnected}</span></div>
          <div className="route-coverage-grid">{data.targetMarketCoverage.routes.map(coverage => <article key={coverage.routeCode} data-complete={coverage.marketComplete}><h3>{routeLabels[coverage.routeCode] ?? coverage.routeCode}</h3><p>{coverage.connected} 已接入 · {coverage.partial} 部分 · {coverage.failed} 失败 · {coverage.notConnected} 未接入</p><strong>{coverage.marketComplete ? '目标目录已全部接入' : '覆盖有缺口，零岗位不能解释为零机会'}</strong></article>)}</div>
          <p className="analysis-coverage">本次结论实际使用：{data.analysisCoverage.sourceCount} 个来源、{data.analysisCoverage.eventCount} 个招聘事件、{data.analysisCoverage.jobCount} 个岗位；其中 {data.analysisCoverage.evidenceCompleteJobs} 个岗位关键证据完整。</p>
          {!data.configuredCoverage.complete && <aside className="coverage-banner" role="status"><strong>已配置来源年度仍待补齐</strong><p>{data.dataCoverage.warnings.join(' ')}</p><span>{data.configuredCoverage.completeSourceYearCount}/{data.configuredCoverage.sourceYearCount} 已完成</span></aside>}
        </section>

        <section className="plan-section scenario-section"><header><p className="section-number">学历与认证阶段</p><h2>当前是境外硕士在读，不再误写成本科阶段</h2></header><ScenarioRail current={data.currentScenario} future={data.futureScenarios} /></section>

        <div className="planning-split">
          <section className="plan-section age-section"><header><p className="section-number">年龄窗口</p><h2>按完整生日计算</h2></header><ul>{data.ageWindows.filter(window => window.maximumAge === 35 || (window.year === 2031 && window.maximumAge === 38)).map(window => <li key={`${window.year}-${window.maximumAge}`} data-eligible={window.eligible}><time dateTime={window.referenceDate}>{window.year}</time><div><strong>{window.label}</strong><small>参考日 {window.referenceDate} · 实龄 {window.candidateAge}</small></div></li>)}</ul><p className="conditional-note">这是规划参考日推演；正式报名以当年公告为准。</p></section>
          <section className="plan-section history-section"><header><p className="section-number">2024—2026 官方历史切片</p><h2>规模参考，不是录取概率</h2></header><div className="history-ledger" role="table" aria-label="年度历史岗位"><div className="history-row history-head" role="row"><span>年度</span><span>岗位行</span><span>独立招聘</span><span>正式用工</span><span>覆盖</span></div>{data.historicalSummary.map(summary => <div className="history-row" role="row" key={summary.year}><strong>{summary.year}</strong><span>{historyUnavailable ? '—' : summary.jobCount}</span><span>{historyUnavailable ? '—' : summary.eventCount}</span><span>{historyUnavailable ? '—' : summary.formalJobCount}</span><span>{historyUnavailable ? '读取失败' : summary.coverageComplete ? '已完成' : '待补齐'}</span></div>)}</div></section>
        </div>

        <section className="plan-section risk-section"><header><p className="section-number">资格风险</p><h2>先解决会改变“能不能报”的事项</h2></header><div>{data.qualificationRisks.map(risk => <article key={risk.code} data-severity={risk.severity}><span>{risk.severity === 'HIGH' ? '优先处理' : '需要确认'}</span><h3>{risk.title}</h3><p>{risk.detail}</p></article>)}</div></section>

        <section className="plan-section action-section" id="actions"><header><p className="section-number">从现在到第二个完整周期</p><h2>行动时间线</h2></header><ol>{data.actionTimeline.map((action, index) => <li key={`${action.startsOn}-${action.title}`} data-now={action.status === 'NOW'}><span>{String(index + 1).padStart(2, '0')}</span><div><time dateTime={action.startsOn}>{action.startsOn} — {action.endsOn}</time><h3>{action.title}</h3><p>{action.detail}</p></div></li>)}</ol></section>

        <section className="plan-section evidence-section"><header><p className="section-number">官方依据</p><h2>代表岗位可下钻查看历史实际与目标年份类比</h2></header><div className="evidence-list">{data.recommendedRoutes.flatMap(route => route.representativeJobs.map(job => ({ ...job, route: route.label }))).map(job => <article key={job.jobId}><span>{job.year} · {job.route}</span><h3><Link to={`/jobs/${job.jobId}`}>{job.title} · {job.organizationName}</Link></h3><p>历史实际：{job.historicalActual.outcome} · {data.targetYear} 类比：{job.targetYearAnalog.outcome}</p><a href={job.sourceUrl} target="_blank" rel="noreferrer">打开官方来源 ↗</a></article>)}</div></section>
      </>}
    </AsyncState>
  </main>
}
