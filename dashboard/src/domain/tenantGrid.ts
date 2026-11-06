import type { TenantPage, TenantRow, TenantStatus } from './snapshot.ts'
import type { RowChange, TierChangeFailure, TierChanges } from './tierChange.ts'
import { usagePercent } from './tenantUsage.ts'
import type { TierOption } from './tiers.ts'

export interface GridRow {
  readonly id: number
  readonly name: string
  readonly tier: string
  readonly limit: number
  readonly used: number | null
  readonly rejected: number | null
  readonly status: TenantStatus | null
  readonly usagePercent: number | null
  readonly tierOptions: readonly TierOption[]
  readonly saving: boolean
  readonly failure: TierChangeFailure | null
  readonly selectorEnabled: boolean
}

export type TenantGridView =
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'rows'; readonly rows: readonly GridRow[]; readonly shownCount: number; readonly total: number }

export function tenantGridOf(
  page: TenantPage,
  changes: TierChanges,
  tiers: readonly TierOption[] | null,
  controlsEnabled: boolean,
): TenantGridView {
  if (!page.rowsAvailable) {
    return { kind: 'unavailable' }
  }
  const rows = (page.rows ?? []).map((row) => gridRowOf(row, changes, tiers, controlsEnabled))
  return { kind: 'rows', rows, shownCount: rows.length, total: page.total ?? rows.length }
}

function gridRowOf(
  row: TenantRow,
  changes: TierChanges,
  tiers: readonly TierOption[] | null,
  controlsEnabled: boolean,
): GridRow {
  const change = changes.get(row.id)
  const saving = change?.kind === 'saving'
  const tier = displayedTierOf(row, change)
  const limit = change?.kind === 'confirmed' ? change.limit : row.limit
  return {
    id: row.id,
    name: row.name,
    tier,
    limit,
    used: row.used,
    rejected: row.rejected,
    status: row.status,
    usagePercent: usagePercent(row.used, limit),
    tierOptions: tiers ?? [{ name: tier, limit }],
    saving,
    failure: change?.kind === 'failed' ? change.failure : null,
    selectorEnabled: controlsEnabled && tiers !== null && !saving,
  }
}

function displayedTierOf(row: TenantRow, change: RowChange | undefined): string {
  switch (change?.kind) {
    case 'saving':
      return change.requested
    case 'confirmed':
      return change.tier
    default:
      return row.tier
  }
}
