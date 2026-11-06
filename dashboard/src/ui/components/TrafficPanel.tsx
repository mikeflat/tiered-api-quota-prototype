import type { SecondTraffic } from '../../domain/snapshot.ts'
import { currentSecondOf } from '../../domain/trafficSummary.ts'
import { formatCount, missingValue } from '../format.ts'
import { TrafficTrend } from './TrafficTrend.tsx'

export function TrafficPanel({ perSecond }: { perSecond: readonly SecondTraffic[] }) {
  const current = currentSecondOf(perSecond)
  return (
    <>
      <dl className="figures">
        <Rate term="Allowed" kind="allowed" value={current?.allowed ?? null} />
        <Rate term="Rejected" kind="rejected" value={current?.rejected ?? null} />
      </dl>
      <TrafficTrend perSecond={perSecond} />
    </>
  )
}

function Rate({ term, kind, value }: { term: string; kind: 'allowed' | 'rejected'; value: number | null }) {
  return (
    <div className={`figure figure-${kind}`}>
      <dt>{term}</dt>{' '}
      <dd>
        {value === null ? (
          missingValue
        ) : (
          <>
            {formatCount(value)}
            <span className="unit"> req/s</span>
          </>
        )}
      </dd>
    </div>
  )
}
