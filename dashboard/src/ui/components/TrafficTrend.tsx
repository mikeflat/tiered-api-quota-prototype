import type { SecondTraffic } from '../../domain/snapshot.ts'
import { trafficPeaksOf } from '../../domain/trafficSummary.ts'
import { type TrendPoint, trendLinesOf, trendSlots } from '../../domain/trendGeometry.ts'
import { formatCount } from '../format.ts'

const trendSize = { width: 600, height: 120 }

export function TrafficTrend({ perSecond }: { perSecond: readonly SecondTraffic[] }) {
  const lines = trendLinesOf(perSecond, trendSize)
  return (
    <figure className="trend">
      <svg
        className="trend-chart"
        role="img"
        aria-label={trendDescriptionOf(perSecond)}
        viewBox={`0 0 ${trendSize.width} ${trendSize.height}`}
        preserveAspectRatio="none"
      >
        <polyline className="trend-line trend-line-allowed" points={pointsAttribute(lines.allowed)} />
        <polyline className="trend-line trend-line-rejected" points={pointsAttribute(lines.rejected)} />
      </svg>
      <figcaption className="trend-legend">
        <span className="legend-item legend-allowed">Allowed</span>
        <span className="legend-item legend-rejected">Rejected</span>
      </figcaption>
    </figure>
  )
}

function trendDescriptionOf(perSecond: readonly SecondTraffic[]): string {
  const peaks = trafficPeaksOf(perSecond.slice(-trendSlots))
  return `Requests per second over the last ${trendSlots} seconds, peak ${formatCount(peaks.allowed)} allowed and ${formatCount(peaks.rejected)} rejected`
}

function pointsAttribute(points: readonly TrendPoint[]): string {
  return points.map((point) => `${point.x},${point.y}`).join(' ')
}
