import type { TenantRow } from '../../src/domain/snapshot.ts'
import type { ChangedTier, TierOption } from '../../src/domain/tiers.ts'

export const defaultTiers: readonly TierOption[] = [
  { name: 'Bronze', limit: 100 },
  { name: 'Silver', limit: 1000 },
  { name: 'Gold', limit: 10000 },
]

export function tenantRow(overrides: Partial<TenantRow> = {}): TenantRow {
  return { id: 1, name: 'Acme Corp', tier: 'Bronze', limit: 100, used: 85, rejected: 0, status: 'near', ...overrides }
}

export function changedTier(overrides: Partial<ChangedTier> = {}): ChangedTier {
  return { id: 1, name: 'Acme Corp', tier: 'Gold', limit: 10000, ...overrides }
}
