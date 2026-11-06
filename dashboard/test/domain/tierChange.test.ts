import { describe, expect, it } from 'vitest'
import { nextTierChanges, noTierChanges, type TierChanges } from '../../src/domain/tierChange.ts'
import { changedTier, tenantRow } from '../support/tiers.ts'

const saving: TierChanges = nextTierChanges(noTierChanges, { kind: 'requested', tenantId: 1, tier: 'Gold' })
const confirmed: TierChanges = nextTierChanges(saving, { kind: 'succeeded', tenantId: 1, changed: changedTier() })
const failed: TierChanges = nextTierChanges(saving, { kind: 'failed', tenantId: 1, failure: { kind: 'notFound' } })

describe('nextTierChanges', () => {
  it('request_marks_row_saving', () => {
    expect(saving.get(1)).toEqual({ kind: 'saving', requested: 'Gold' })
  })

  it('success_confirms_requested_tier_and_limit', () => {
    expect(confirmed.get(1)).toEqual({ kind: 'confirmed', tier: 'Gold', limit: 10000 })
  })

  it('failure_records_failure', () => {
    expect(failed.get(1)).toEqual({ kind: 'failed', failure: { kind: 'notFound' } })
  })

  it('new_request_replaces_failure', () => {
    expect(nextTierChanges(failed, { kind: 'requested', tenantId: 1, tier: 'Silver' }).get(1)).toEqual({
      kind: 'saving',
      requested: 'Silver',
    })
  })

  it('rows_reporting_confirmed_tier_clear_confirmation', () => {
    const next = nextTierChanges(confirmed, { kind: 'rowsReceived', rows: [tenantRow({ tier: 'Gold', limit: 10000 })] })

    expect(next.has(1)).toBe(false)
  })

  it('rows_with_old_tier_keep_confirmation', () => {
    const next = nextTierChanges(confirmed, { kind: 'rowsReceived', rows: [tenantRow({ tier: 'Bronze' })] })

    expect(next).toBe(confirmed)
  })

  it('late_outcome_for_row_not_saving_is_ignored', () => {
    expect(nextTierChanges(noTierChanges, { kind: 'succeeded', tenantId: 1, changed: changedTier() })).toBe(noTierChanges)
    expect(nextTierChanges(failed, { kind: 'failed', tenantId: 1, failure: { kind: 'timedOut' } })).toBe(failed)
    expect(nextTierChanges(confirmed, { kind: 'failed', tenantId: 1, failure: { kind: 'timedOut' } })).toBe(confirmed)
  })

  it('other_rows_are_untouched', () => {
    const both = nextTierChanges(failed, { kind: 'requested', tenantId: 2, tier: 'Silver' })

    expect(both.get(1)).toEqual({ kind: 'failed', failure: { kind: 'notFound' } })
    expect(both.get(2)).toEqual({ kind: 'saving', requested: 'Silver' })
    expect(failed.has(2)).toBe(false)
  })
})
