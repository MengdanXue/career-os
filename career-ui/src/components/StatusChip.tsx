import type { ReactNode } from 'react'

export function StatusChip({ tone = 'neutral', children }: { tone?: 'positive' | 'warning' | 'negative' | 'neutral'; children: ReactNode }) {
  return <span className="status-chip" data-tone={tone}>{children}</span>
}
