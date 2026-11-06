import type { AdminSnapshot, TrafficMetrics } from '../../src/domain/snapshot.ts'

export const healthyMetrics: TrafficMetrics = {
  perSecond: [{ second: 6_000_044, allowed: 3, rejected: 1 }],
  totals: { allowed: 120, rejected: 7 },
  tenantsNear: 2,
  tenantsOver: 1,
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
