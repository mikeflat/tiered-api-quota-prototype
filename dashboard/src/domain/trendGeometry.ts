import type { SecondTraffic } from './snapshot.ts'
import { trafficPeaksOf } from './trafficSummary.ts'

export const trendSlots = 60

export interface TrendPoint {
  readonly x: number
  readonly y: number
}

export interface TrendSize {
  readonly width: number
  readonly height: number
}

export interface TrendLines {
  readonly allowed: readonly TrendPoint[]
  readonly rejected: readonly TrendPoint[]
}

export function trendLinesOf(perSecond: readonly SecondTraffic[], size: TrendSize): TrendLines {
  const shown = perSecond.slice(-trendSlots)
  const peaks = trafficPeaksOf(shown)
  const scaleMax = Math.max(peaks.allowed, peaks.rejected, 1)
  const firstSlot = trendSlots - shown.length
  const slotWidth = size.width / (trendSlots - 1)

  const pointAt = (slotOffset: number, value: number): TrendPoint => ({
    x: toTenths((firstSlot + slotOffset) * slotWidth),
    y: toTenths(size.height - (value / scaleMax) * size.height),
  })

  return {
    allowed: shown.map((second, offset) => pointAt(offset, second.allowed)),
    rejected: shown.map((second, offset) => pointAt(offset, second.rejected)),
  }
}

export interface TrendHover {
  /** Index into the shown (last {@link trendSlots}) seconds. */
  readonly offset: number
  /** Horizontal position of that second across the chart, 0 (left) to 1 (right). */
  readonly position: number
  /** How many seconds before the newest shown second; 0 is the newest. */
  readonly secondsAgo: number
}

/**
 * Maps a horizontal pointer position (0 = left edge, 1 = right edge) to the nearest drawn second,
 * or null when it falls on the empty slots left of a partially filled minute.
 */
export function trendHoverAt(fraction: number, shownCount: number): TrendHover | null {
  const shown = Math.min(shownCount, trendSlots)
  if (shown === 0 || !Number.isFinite(fraction)) {
    return null
  }
  const slot = Math.round(Math.min(Math.max(fraction, 0), 1) * (trendSlots - 1))
  const offset = slot - (trendSlots - shown)
  if (offset < 0) {
    return null
  }
  return { offset, position: slot / (trendSlots - 1), secondsAgo: shown - 1 - offset }
}

function toTenths(value: number): number {
  return Math.round(value * 10) / 10
}
