export function App() {
  return (
    <div className="workbench-shell">
      <header className="product-header">
        <a className="product-mark" href="/" aria-label="Career OS 首页">
          <span className="product-monogram" aria-hidden="true">C</span>
          <span>
            <strong>Career OS</strong>
            <small>稳定技术岗位决策台</small>
          </span>
        </a>
        <p className="source-status"><span aria-hidden="true" /> 官方岗位数据 · 本地运行</p>
      </header>

      <nav className="primary-navigation" aria-label="主要导航">
        <a className="active" href="/">今天</a>
        <a href="/opportunities">机会池</a>
        <a href="/updates">更新岗位库</a>
        <a href="/profile">我的资料</a>
      </nav>

      <main className="shell-content">
        <p className="eyebrow">DAILY BRIEF · 今日简报</p>
        <h1>Career OS</h1>
        <p className="lead">把杭州及浙江半体制技术岗位，变成有证据、可复核的职业选择。</p>
        <section className="empty-brief" aria-labelledby="brief-title">
          <div>
            <p className="section-number">01</p>
            <h2 id="brief-title">工作台正在接入</h2>
          </div>
          <p>下一步将连接你的候选人资料、岗位分层与每日变化。</p>
        </section>
      </main>
    </div>
  )
}
