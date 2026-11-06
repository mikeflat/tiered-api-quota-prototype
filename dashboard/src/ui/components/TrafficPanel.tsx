import type { SecondTraffic } from '../../domain/snapshot.ts'
import { currentSecondOf } from '../../domain/trafficSummary.ts'
import { formatCount, missingValue } from '../format.ts'
import { TrafficTrend } from './TrafficTrend.tsx'

export function TrafficPanel({ perSecond }: { perSecond: readonly SecondTraffic[] }) {
  const current = currentSecondOf(perSecond)
  return (
    <>
      <dl className="figures">
        <Rate term="Allowed" value={current?.allowed ?? null} />
        <Rate term="Rejected" value={current?.rejected ?? null} />
      </dl>
      <TrafficTrend perSecond={perSecond} />
    </>
  )
}

function Rate({ term, value }: { term: string; value: number | null }) {
  return (
    <div className="figure">
      <dt>{term}</dt> <dd>{value === null ? missingValue : `${formatCount(value)} req/s`}</dd>
    </div>
  )
}
