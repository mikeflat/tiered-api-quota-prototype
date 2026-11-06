import type { TrafficMetrics } from '../../domain/snapshot.ts'
import { formatCount } from '../format.ts'

type TotalKind = 'allowed' | 'rejected' | 'near' | 'over'

export function PeriodTotalsPanel({ metrics }: { metrics: TrafficMetrics }) {
  return (
    <dl className="figures figures-tiles">
      <Total term="Allowed" kind="allowed" value={metrics.totals.allowed} />
      <Total term="Rejected" kind="rejected" value={metrics.totals.rejected} />
      <Total term="Near limit" kind="near" value={metrics.tenantsNear} />
      <Total term="Over limit" kind="over" value={metrics.tenantsOver} />
    </dl>
  )
}

function Total({ term, kind, value }: { term: string; kind: TotalKind; value: number }) {
  return (
    <div className={`figure figure-${kind}`}>
      <dt>{term}</dt> <dd>{formatCount(value)}</dd>
    </div>
  )
}
