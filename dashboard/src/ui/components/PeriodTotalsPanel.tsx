import type { TrafficMetrics } from '../../domain/snapshot.ts'
import { formatCount } from '../format.ts'

export function PeriodTotalsPanel({ metrics }: { metrics: TrafficMetrics }) {
  return (
    <dl className="figures">
      <Total term="Allowed" value={metrics.totals.allowed} />
      <Total term="Rejected" value={metrics.totals.rejected} />
      <Total term="Near limit" value={metrics.tenantsNear} />
      <Total term="Over limit" value={metrics.tenantsOver} />
    </dl>
  )
}

function Total({ term, value }: { term: string; value: number }) {
  return (
    <div className="figure">
      <dt>{term}</dt> <dd>{formatCount(value)}</dd>
    </div>
  )
}
