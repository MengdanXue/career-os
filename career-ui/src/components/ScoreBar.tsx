export function ScoreBar({ label, score, maximum = 100 }: { label: string; score: number | null; maximum?: number }) {
  const percent = score === null ? null : Math.max(0, Math.min(100, Math.round((score / maximum) * 100)))
  return (
    <div className="score-bar">
      <div><span>{label}</span><strong>{score === null ? '待核实' : `${score}/${maximum}`}</strong></div>
      <div className="score-track" aria-hidden="true"><span style={{ width: `${percent ?? 0}%` }} /></div>
    </div>
  )
}
