import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './styles/tokens.css'
import './styles/base.css'
import './styles/components.css'
import './styles/profile.css'
import './styles/opportunities.css'
import './styles/agent.css'
import './styles/today.css'
import './styles/updates.css'
import './styles/planning.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
