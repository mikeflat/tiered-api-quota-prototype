import { pageCountOf } from './pagination.ts'
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

export interface UnavailableGrid {
  readonly kind: 'unavailable'
  readonly canClearStatus: boolean
}

export interface RowsGrid {
  readonly kind: 'rows'
  readonly rows: readonly GridRow[]
  readonly shownCount: number
  readonly total: number
  readonly pageNumber: number
  readonly pageCount: number
  readonly canPrevious: boolean
  readonly canNext: boolean
  readonly updating: boolean
}

export type TenantGridView = UnavailableGrid | RowsGrid

export interface TenantGridInputs {
  readonly page: TenantPage
  readonly changes: TierChanges
  readonly tiers: readonly TierOption[] | null
  readonly controlsEnabled: boolean
  readonly updating: boolean
}

export function tenantGridOf({ page, changes, tiers, controlsEnabled, updating }: TenantGridInputs): TenantGridView {
  if (!page.rowsAvailable) {
    return { kind: 'unavailable', canClearStatus: controlsEnabled }
  }
  const rows = (page.rows ?? []).map((row) => gridRowOf(row, changes, tiers, controlsEnabled))
  const total = page.total ?? rows.length
  const pageCount = pageCountOf(total, page.size)
  return {
    kind: 'rows',
    rows,
    shownCount: rows.length,
    total,
    pageNumber: Math.min(page.page, pageCount - 1) + 1,
    pageCount,
    canPrevious: controlsEnabled && page.page > 0,
    canNext: controlsEnabled && page.page < pageCount - 1,
    updating,
  }
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
