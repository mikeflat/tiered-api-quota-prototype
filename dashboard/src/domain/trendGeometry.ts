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

function toTenths(value: number): number {
  return Math.round(value * 10) / 10
}
