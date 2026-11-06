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

function gridOf(
  tenantPage: TenantPage,
  changes: TierChanges = noTierChanges,
  tiers: typeof defaultTiers | null = defaultTiers,
  controlsEnabled = true,
  updating = false,
): TenantGridView {
  return tenantGridOf({ page: tenantPage, changes, tiers, controlsEnabled, updating })
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
    const rows = rowsOf(gridOf(page, noTierChanges, defaultTiers, true))

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

    const rows = rowsOf(gridOf(page, changes, defaultTiers, true))

    expect(rows[0]).toMatchObject({ tier: 'Gold', saving: true, selectorEnabled: false })
    expect(rows[1]).toMatchObject({ saving: false, selectorEnabled: true })
  })

  it('confirmed_row_uses_new_tier_and_limit_for_percent', () => {
    const changes = changesFor(
      { kind: 'requested', tenantId: 1, tier: 'Gold' },
      { kind: 'succeeded', tenantId: 1, changed: changedTier() },
    )

    expect(rowsOf(gridOf(page, changes, defaultTiers, true))[0]).toMatchObject({
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

    expect(rowsOf(gridOf(page, changes, defaultTiers, true))[0]).toMatchObject({
      tier: 'Bronze',
      limit: 100,
      failure: { kind: 'serverError', status: 502 },
      selectorEnabled: true,
    })
  })

  it('null_usage_rows_have_no_percent_and_enabled_selectors', () => {
    const withoutUsage: TenantPage = { ...page, rows: [tenantRow({ used: null, rejected: null, status: null })] }

    expect(rowsOf(gridOf(withoutUsage, noTierChanges, defaultTiers, true))[0]).toMatchObject({
      used: null,
      rejected: null,
      status: null,
      usagePercent: null,
      selectorEnabled: true,
    })
  })

  it('controls_disabled_disables_every_selector', () => {
    const rows = rowsOf(gridOf(page, noTierChanges, defaultTiers, false))

    expect(rows.every((row) => !row.selectorEnabled)).toBe(true)
  })

  it('missing_tier_list_offers_only_current_tier_and_disables_selector', () => {
    const row = rowsOf(gridOf(page, noTierChanges, null, true))[1]

    expect(row?.tierOptions).toEqual([{ name: 'Silver', limit: 1000 }])
    expect(row?.selectorEnabled).toBe(false)
  })

  it('unavailable_rows_give_unavailable_view', () => {
    const unavailable: TenantPage = { ...page, rowsAvailable: false, rows: null, total: null }

    expect(gridOf(unavailable, noTierChanges, defaultTiers, true)).toEqual({ kind: 'unavailable', canClearStatus: true })
  })

  it('shown_count_and_total_come_from_page', () => {
    expect(gridOf(page, noTierChanges, defaultTiers, true)).toMatchObject({ shownCount: 2, total: 15 })
    expect(tenantGridOf({ page: { ...page, rows: [], total: 0 }, changes: noTierChanges, tiers: defaultTiers, controlsEnabled: true, updating: false })).toMatchObject({
      shownCount: 0,
      total: 0,
    })
  })

  it('grid_reports_page_number_and_count', () => {
    expect(gridOf(page)).toMatchObject({ pageNumber: 1, pageCount: 2 })
    expect(gridOf({ ...page, page: 1 })).toMatchObject({ pageNumber: 2, pageCount: 2 })
    expect(gridOf({ ...page, rows: [], total: 0 })).toMatchObject({ pageNumber: 1, pageCount: 1 })
  })

  it('pager_buttons_follow_position_and_controls', () => {
    expect(gridOf(page)).toMatchObject({ canPrevious: false, canNext: true })
    expect(gridOf({ ...page, page: 1 })).toMatchObject({ canPrevious: true, canNext: false })
    expect(gridOf({ ...page, page: 1 }, noTierChanges, defaultTiers, false)).toMatchObject({
      canPrevious: false,
      canNext: false,
    })
  })

  it('grid_marks_updating', () => {
    expect(gridOf(page, noTierChanges, defaultTiers, true, true)).toMatchObject({ updating: true })
    expect(gridOf(page)).toMatchObject({ updating: false })
  })

  it('unavailable_view_offers_clearing_status', () => {
    const unavailable: TenantPage = { ...page, rowsAvailable: false, rows: null, total: null }

    expect(gridOf(unavailable)).toEqual({ kind: 'unavailable', canClearStatus: true })
    expect(gridOf(unavailable, noTierChanges, defaultTiers, false)).toEqual({ kind: 'unavailable', canClearStatus: false })
  })
})
