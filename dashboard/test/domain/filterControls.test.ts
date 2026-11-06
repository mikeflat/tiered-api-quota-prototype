import { describe, expect, it } from 'vitest'
import { emptyDrafts, filterControlsOf } from '../../src/domain/filterControls.ts'
import { initialTenantQuery, noFilters, withFilters } from '../../src/domain/tenantQuery.ts'
import { defaultTiers } from '../support/tiers.ts'

const goldOver = withFilters(initialTenantQuery, { ...noFilters, tier: 'Gold', status: 'over' })

describe('filterControlsOf', () => {
  it('controls_show_drafts_and_applied_choices', () => {
    expect(filterControlsOf({ nameText: 'ban', idText: '3' }, goldOver, defaultTiers, true, true)).toEqual({
      nameText: 'ban',
      idText: '3',
      idInvalid: false,
      tier: 'Gold',
      status: 'over',
      tierOptions: ['Bronze', 'Silver', 'Gold'],
      enabled: true,
      statusEnabled: true,
    })
  })

  it('invalid_id_draft_is_flagged', () => {
    expect(filterControlsOf({ nameText: '', idText: 'acme' }, initialTenantQuery, defaultTiers, true, true).idInvalid).toBe(
      true,
    )
    expect(filterControlsOf(emptyDrafts, initialTenantQuery, defaultTiers, true, true).idInvalid).toBe(false)
  })

  it('tier_options_come_from_tier_list_or_are_empty', () => {
    expect(filterControlsOf(emptyDrafts, initialTenantQuery, null, true, true).tierOptions).toEqual([])
  })

  it('controls_disabled_when_not_live', () => {
    expect(filterControlsOf(emptyDrafts, goldOver, defaultTiers, false, true)).toMatchObject({
      enabled: false,
      statusEnabled: false,
      tier: 'Gold',
    })
  })

  it('status_disabled_without_metrics_but_keeps_value', () => {
    expect(filterControlsOf(emptyDrafts, goldOver, defaultTiers, true, false)).toMatchObject({
      enabled: true,
      statusEnabled: false,
      status: 'over',
    })
  })
})
