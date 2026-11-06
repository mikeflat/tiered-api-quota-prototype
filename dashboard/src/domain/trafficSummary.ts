import type { SecondTraffic } from './snapshot.ts'

export interface TrafficPeaks {
  readonly allowed: number
  readonly rejected: number
}

export function currentSecondOf(perSecond: readonly SecondTraffic[]): SecondTraffic | null {
  return perSecond.at(-1) ?? null
}

export function trafficPeaksOf(perSecond: readonly SecondTraffic[]): TrafficPeaks {
  return {
    allowed: Math.max(0, ...perSecond.map((second) => second.allowed)),
    rejected: Math.max(0, ...perSecond.map((second) => second.rejected)),
  }
}
