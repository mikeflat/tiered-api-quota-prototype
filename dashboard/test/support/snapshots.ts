import type { AdminSnapshot, SecondTraffic, TrafficMetrics } from '../../src/domain/snapshot.ts'

export interface SecondCounts {
  readonly allowed: number
  readonly rejected: number
}

export function minuteOfTraffic(newestSecond: number, countsAt: (secondsAgo: number) => SecondCounts): SecondTraffic[] {
  return Array.from({ length: 60 }, (_, index) => {
    const secondsAgo = 59 - index
    return { second: newestSecond - secondsAgo, ...countsAt(secondsAgo) }
  })
}

export const healthyMetrics: TrafficMetrics = {
  perSecond: [{ second: 6_000_044, allowed: 3, rejected: 1 }],
  totals: { allowed: 120, rejected: 7 },
  tenantsNear: 2,
  tenantsOver: 1,
}

export function metricsWith(overrides: Partial<TrafficMetrics> = {}): TrafficMetrics {
  return { ...healthyMetrics, ...overrides }
}

export function snapshotWith(overrides: Partial<AdminSnapshot> = {}): AdminSnapshot {
  return {
    period: { index: 100_000, endsAt: '1970-03-11T10:41:00Z', secondsLeft: 15 },
    metrics: healthyMetrics,
    tenants: {
      rowsAvailable: true,
      rows: [{ id: 1, name: 'Acme Corp', tier: 'Bronze', limit: 100, used: 85, rejected: 0, status: 'near' }],
      total: 1,
      page: 0,
      size: 10,
    },
    ...overrides,
  }
}

export function snapshotWithoutMetrics(): AdminSnapshot {
  return snapshotWith({ metrics: null })
}
