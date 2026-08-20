import { NavLink, Outlet } from 'react-router-dom'

const navItems = [
  { to: '/', label: '今天', end: true },
  { to: '/opportunities', label: '机会池' },
  { to: '/updates', label: '更新岗位库' },
  { to: '/profile', label: '我的资料' },
]

export function AppShell() {
  return (
    <div className="workbench-shell">
      <header className="product-header">
        <NavLink className="product-mark" to="/" aria-label="Career OS 首页">
          <span className="product-monogram" aria-hidden="true">C</span>
          <span><strong>Career OS</strong><small>稳定技术岗位决策台</small></span>
        </NavLink>
        <p className="source-status"><span aria-hidden="true" /> 官方岗位数据 · 本地运行</p>
      </header>
      <nav className="primary-navigation" aria-label="主要导航">
        {navItems.map(item => <NavLink key={item.to} end={item.end} to={item.to}>{item.label}</NavLink>)}
      </nav>
      <Outlet />
    </div>
  )
}
