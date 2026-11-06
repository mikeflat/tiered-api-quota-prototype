import { parseIdInput } from './idInput.ts'
import type { TenantStatus } from './snapshot.ts'
import type { TenantQuery } from './tenantQuery.ts'
import type { TierOption } from './tiers.ts'

export interface FilterDrafts {
  readonly nameText: string
  readonly idText: string
}

export const emptyDrafts: FilterDrafts = { nameText: '', idText: '' }

export interface FilterControlsView {
  readonly nameText: string
  readonly idText: string
  readonly idInvalid: boolean
  readonly tier: string | null
  readonly status: TenantStatus | null
  readonly tierOptions: readonly string[]
  readonly enabled: boolean
  readonly statusEnabled: boolean
}

export function filterControlsOf(
  drafts: FilterDrafts,
  query: TenantQuery,
  tiers: readonly TierOption[] | null,
  controlsEnabled: boolean,
  metricsAvailable: boolean,
): FilterControlsView {
  return {
    nameText: drafts.nameText,
    idText: drafts.idText,
    idInvalid: parseIdInput(drafts.idText).kind === 'invalid',
    tier: query.filters.tier,
    status: query.filters.status,
    tierOptions: (tiers ?? []).map((tier) => tier.name),
    enabled: controlsEnabled,
    statusEnabled: controlsEnabled && metricsAvailable,
  }
}
