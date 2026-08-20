import type { OpportunityTier } from '../../api/contracts'

const supported = new Set<OpportunityTier>(['T1', 'T2', 'T3', 'EXCLUDED'])

export function tierFromSearch(search: string, fallback: OpportunityTier = 'T1') {
  const value = new URLSearchParams(search).get('tier') as OpportunityTier | null
  return value && supported.has(value) ? value : fallback
}

export function searchWithTier(search: string, tier: OpportunityTier) {
  const parameters = new URLSearchParams(search)
  parameters.set('tier', tier)
  parameters.delete('page')
  return `?${parameters}`
}
