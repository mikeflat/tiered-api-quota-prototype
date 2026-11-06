import type { HealthReport } from '../domain/enforcement.ts'
import type { AdminSnapshot } from '../domain/snapshot.ts'
import type { TenantQuery } from '../domain/tenantQuery.ts'
import type { ChangedTier, TierOption } from '../domain/tiers.ts'

export interface SnapshotSource {
  fetchSnapshot(query: TenantQuery, signal: AbortSignal): Promise<AdminSnapshot>
}

export interface HealthSource {
  fetchHealth(signal: AbortSignal): Promise<HealthReport>
}

export type Cancel = () => void

export interface Scheduler {
  now(): number
  schedule(callback: () => void, delayMs: number): Cancel
}

export interface TierAdministration {
  fetchTiers(signal: AbortSignal): Promise<readonly TierOption[]>
  changeTier(tenantId: number, tier: string, signal: AbortSignal): Promise<ChangedTier>
}
