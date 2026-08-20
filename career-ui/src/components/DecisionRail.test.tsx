import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DecisionRail } from './DecisionRail'

describe('DecisionRail', () => {
  it('reads the four decision gates in the same order a person evaluates them', () => {
    render(<DecisionRail
      eligibility="ELIGIBLE"
      coveragePercent={72}
      tier="T1"
      deadline="2026-09-18"
    />)

    const rail = screen.getByRole('list', { name: '决策路径' })
    const gates = within(rail).getAllByRole('listitem')
    expect(gates).toHaveLength(4)
    expect(gates[0]).toHaveTextContent('资格通过')
    expect(gates[1]).toHaveTextContent('证据覆盖 72%')
    expect(gates[2]).toHaveTextContent('T1 优先关注')
    expect(gates[3]).toHaveTextContent('截止 2026-09-18')
  })

  it('labels missing evidence and deadline without inventing a zero', () => {
    render(<DecisionRail eligibility="UNKNOWN" coveragePercent={null} tier="REVIEW" deadline={null} />)

    expect(screen.getByText('资格待核实')).toBeInTheDocument()
    expect(screen.getByText('证据覆盖待核实')).toBeInTheDocument()
    expect(screen.getByText('截止时间待核实')).toBeInTheDocument()
    expect(screen.queryByText(/0%/)).not.toBeInTheDocument()
  })
})
