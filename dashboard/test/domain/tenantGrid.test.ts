import { describe, expect, it } from 'vitest'
import type { TenantPage } from '../../src/domain/snapshot.ts'
import { type GridRow, tenantGridOf, type TenantGridView } from '../../src/domain/tenantGrid.ts'
import { nextTierChanges, noTierChanges, type TierChanges } from '../../src/domain/tierChange.ts'
import { changedTier, defaultTiers, tenantRow } from '../support/tiers.ts'

const page: TenantPage = {
  rowsAvailable: true,
  rows: [
    tenantRow(),
    tenantRow({ id: 2, name: 'Blue Bank', tier: 'Silver', limit: 1000, used: 120, rejected: 3, status: 'ok' }),
  ],
  total: 15,
  page: 0,
  size: 10,
}

function rowsOf(grid: TenantGridView): readonly GridRow[] {
  if (grid.kind !== 'rows') {
    throw new Error('grid has no rows')
  }
  return grid.rows
}

function changesFor(...events: Parameters<typeof nextTierChanges>[1][]): TierChanges {
  return events.reduce(nextTierChanges, noTierChanges)
}

describe('tenantGridOf', () => {
  it('rows_keep_snapshot_order_and_values', () => {
    const rows = rowsOf(tenantGridOf(page, noTierChanges, defaultTiers, true))

    expect(rows.map((row) => row.name)).toEqual(['Acme Corp', 'Blue Bank'])
    expect(rows[0]).toEqual({
      id: 1,
      name: 'Acme Corp',
      tier: 'Bronze',
      limit: 100,
      used: 85,
      rejected: 0,
      status: 'near',
      usagePercent: 85,
      tierOptions: defaultTiers,
      saving: false,
      failure: null,
      selectorEnabled: true,
    })
  })

  it('saving_row_shows_requested_tier_and_disables_selector', () => {
    const changes = changesFor({ kind: 'requested', tenantId: 1, tier: 'Gold' })

    const rows = rowsOf(tenantGridOf(page, changes, defaultTiers, true))

    expect(rows[0]).toMatchObject({ tier: 'Gold', saving: true, selectorEnabled: false })
    expect(rows[1]).toMatchObject({ saving: false, selectorEnabled: true })
  })

  it('confirmed_row_uses_new_tier_and_limit_for_percent', () => {
    const changes = changesFor(
      { kind: 'requested', tenantId: 1, tier: 'Gold' },
      { kind: 'succeeded', tenantId: 1, changed: changedTier() },
    )

    expect(rowsOf(tenantGridOf(page, changes, defaultTiers, true))[0]).toMatchObject({
      tier: 'Gold',
      limit: 10000,
      usagePercent: 0,
      saving: false,
      failure: null,
      selectorEnabled: true,
    })
  })

  it('failed_row_shows_snapshot_tier_and_failure', () => {
    const changes = changesFor(
      { kind: 'requested', tenantId: 1, tier: 'Gold' },
      { kind: 'failed', tenantId: 1, failure: { kind: 'serverError', status: 502 } },
    )

    expect(rowsOf(tenantGridOf(page, changes, defaultTiers, true))[0]).toMatchObject({
      tier: 'Bronze',
      limit: 100,
      failure: { kind: 'serverError', status: 502 },
      selectorEnabled: true,
    })
  })

  it('null_usage_rows_have_no_percent_and_enabled_selectors', () => {
    const withoutUsage: TenantPage = { ...page, rows: [tenantRow({ used: null, rejected: null, status: null })] }

    expect(rowsOf(tenantGridOf(withoutUsage, noTierChanges, defaultTiers, true))[0]).toMatchObject({
      used: null,
      rejected: null,
      status: null,
      usagePercent: null,
      selectorEnabled: true,
    })
  })

  it('controls_disabled_disables_every_selector', () => {
    const rows = rowsOf(tenantGridOf(page, noTierChanges, defaultTiers, false))

    expect(rows.every((row) => !row.selectorEnabled)).toBe(true)
  })

  it('missing_tier_list_offers_only_current_tier_and_disables_selector', () => {
    const row = rowsOf(tenantGridOf(page, noTierChanges, null, true))[1]

    expect(row?.tierOptions).toEqual([{ name: 'Silver', limit: 1000 }])
    expect(row?.selectorEnabled).toBe(false)
  })

  it('unavailable_rows_give_unavailable_view', () => {
    const unavailable: TenantPage = { ...page, rowsAvailable: false, rows: null, total: null }

    expect(tenantGridOf(unavailable, noTierChanges, defaultTiers, true)).toEqual({ kind: 'unavailable' })
  })

  it('shown_count_and_total_come_from_page', () => {
    expect(tenantGridOf(page, noTierChanges, defaultTiers, true)).toMatchObject({ shownCount: 2, total: 15 })
    expect(tenantGridOf({ ...page, rows: [], total: 0 }, noTierChanges, defaultTiers, true)).toMatchObject({
      shownCount: 0,
      total: 0,
    })
  })
})
