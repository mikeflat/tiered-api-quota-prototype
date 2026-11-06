import { type PointerEvent, useState } from 'react'
import type { SecondTraffic } from '../../domain/snapshot.ts'
import { trafficPeaksOf } from '../../domain/trafficSummary.ts'
import { type TrendPoint, trendHoverAt, trendLinesOf, trendSlots } from '../../domain/trendGeometry.ts'
import { formatCount } from '../format.ts'

const trendSize = { width: 600, height: 120 }
const gridLevels = [0, trendSize.height / 2, trendSize.height]

export function TrafficTrend({ perSecond }: { perSecond: readonly SecondTraffic[] }) {
  const [pointerFraction, setPointerFraction] = useState<number | null>(null)
  const shown = perSecond.slice(-trendSlots)
  const lines = trendLinesOf(perSecond, trendSize)
  const hover = pointerFraction === null ? null : trendHoverAt(pointerFraction, shown.length)
  const hovered = hover === null ? undefined : shown[hover.offset]

  const trackPointer = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    setPointerFraction(box.width > 0 ? (event.clientX - box.left) / box.width : null)
  }

  return (
    <figure className="trend">
      <div className="trend-plot" onPointerMove={trackPointer} onPointerLeave={() => setPointerFraction(null)}>
        <svg
          className="trend-chart"
          role="img"
          aria-label={trendDescriptionOf(perSecond)}
          viewBox={`0 0 ${trendSize.width} ${trendSize.height}`}
          preserveAspectRatio="none"
        >
          {gridLevels.map((y) => (
            <line key={y} className="trend-grid" x1={0} x2={trendSize.width} y1={y} y2={y} />
          ))}
          {lines.allowed.length > 0 && (
            <polygon className="trend-area-allowed" points={areaPointsAttribute(lines.allowed)} />
          )}
          <polyline className="trend-line trend-line-allowed" points={pointsAttribute(lines.allowed)} />
          <polyline className="trend-line trend-line-rejected" points={pointsAttribute(lines.rejected)} />
        </svg>
        {hover !== null && hovered !== undefined && (
          <>
            <div className="trend-crosshair" style={{ left: `${hover.position * 100}%` }} aria-hidden="true" />
            <div className="trend-tooltip" style={tooltipPlacement(hover.position)} aria-hidden="true">
              <div className="trend-tooltip-time">{hover.secondsAgo === 0 ? 'now' : `−${hover.secondsAgo}s`}</div>
              <div className="trend-tooltip-row legend-item legend-allowed">
                Allowed <b>{formatCount(hovered.allowed)}</b>
              </div>
              <div className="trend-tooltip-row legend-item legend-rejected">
                Rejected <b>{formatCount(hovered.rejected)}</b>
              </div>
            </div>
          </>
        )}
      </div>
      <div className="trend-axis" aria-hidden="true">
        <span>−{trendSlots}s</span>
        <span>now</span>
      </div>
      <figcaption className="trend-legend">
        <span className="legend-item legend-allowed">Allowed</span>
        <span className="legend-item legend-rejected">Rejected</span>
      </figcaption>
    </figure>
  )
}

function tooltipPlacement(position: number) {
  return position > 0.6 ? { right: `calc(${(1 - position) * 100}% + 12px)` } : { left: `calc(${position * 100}% + 12px)` }
}

function trendDescriptionOf(perSecond: readonly SecondTraffic[]): string {
  const peaks = trafficPeaksOf(perSecond.slice(-trendSlots))
  return `Requests per second over the last ${trendSlots} seconds, peak ${formatCount(peaks.allowed)} allowed and ${formatCount(peaks.rejected)} rejected`
}

function pointsAttribute(points: readonly TrendPoint[]): string {
  return points.map((point) => `${point.x},${point.y}`).join(' ')
}

function areaPointsAttribute(points: readonly TrendPoint[]): string {
  const first = points[0]!
  const last = points.at(-1)!
  return `${pointsAttribute(points)} ${last.x},${trendSize.height} ${first.x},${trendSize.height}`
}
