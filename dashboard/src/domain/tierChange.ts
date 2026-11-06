import type { TenantRow } from './snapshot.ts'
import type { ChangedTier } from './tiers.ts'

export type TierChangeFailure =
  | { readonly kind: 'notFound' }
  | { readonly kind: 'rejected' }
  | { readonly kind: 'serverError'; readonly status: number }
  | { readonly kind: 'timedOut' }
  | { readonly kind: 'unreachable' }

export class TierChangeRefused extends Error {
  readonly status: number

  constructor(status: number) {
    super(`Tier change refused with status ${status}`)
    this.name = 'TierChangeRefused'
    this.status = status
  }
}

export type RowChange =
  | { readonly kind: 'saving'; readonly requested: string }
  | { readonly kind: 'confirmed'; readonly tier: string; readonly limit: number }
  | { readonly kind: 'failed'; readonly failure: TierChangeFailure }

export type TierChanges = ReadonlyMap<number, RowChange>

export type TierChangeEvent =
  | { readonly kind: 'requested'; readonly tenantId: number; readonly tier: string }
  | { readonly kind: 'succeeded'; readonly tenantId: number; readonly changed: ChangedTier }
  | { readonly kind: 'failed'; readonly tenantId: number; readonly failure: TierChangeFailure }
  | { readonly kind: 'rowsReceived'; readonly rows: readonly TenantRow[] }

export const noTierChanges: TierChanges = new Map()

export function nextTierChanges(changes: TierChanges, event: TierChangeEvent): TierChanges {
  switch (event.kind) {
    case 'requested':
      return withChange(changes, event.tenantId, { kind: 'saving', requested: event.tier })
    case 'succeeded':
      return isSaving(changes, event.tenantId)
        ? withChange(changes, event.tenantId, { kind: 'confirmed', tier: event.changed.tier, limit: event.changed.limit })
        : changes
    case 'failed':
      return isSaving(changes, event.tenantId)
        ? withChange(changes, event.tenantId, { kind: 'failed', failure: event.failure })
        : changes
    case 'rowsReceived':
      return withoutCaughtUpConfirmations(changes, event.rows)
  }
}

function isSaving(changes: TierChanges, tenantId: number): boolean {
  return changes.get(tenantId)?.kind === 'saving'
}

function withChange(changes: TierChanges, tenantId: number, change: RowChange): TierChanges {
  const next = new Map(changes)
  next.set(tenantId, change)
  return next
}

function withoutCaughtUpConfirmations(changes: TierChanges, rows: readonly TenantRow[]): TierChanges {
  const caughtUp = rows.filter((row) => {
    const change = changes.get(row.id)
    return change?.kind === 'confirmed' && change.tier === row.tier
  })
  if (caughtUp.length === 0) {
    return changes
  }
  const next = new Map(changes)
  for (const row of caughtUp) {
    next.delete(row.id)
  }
  return next
}
