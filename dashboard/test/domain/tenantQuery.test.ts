import { describe, expect, it } from 'vitest'
import {
  initialTenantQuery,
  noFilters,
  sameQuery,
  tenantPageSize,
  withFilters,
  withPage,
} from '../../src/domain/tenantQuery.ts'

describe('tenantQuery', () => {
  it('initial_query_has_no_filters_and_first_page', () => {
    expect(initialTenantQuery).toEqual({ filters: { name: '', id: null, tier: null, status: null }, page: 0 })
    expect(tenantPageSize).toBe(10)
  })

  it('changing_filters_returns_to_first_page', () => {
    const onSecondPage = withPage(initialTenantQuery, 1)

    expect(withFilters(onSecondPage, { ...noFilters, tier: 'Silver' })).toEqual({
      filters: { ...noFilters, tier: 'Silver' },
      page: 0,
    })
  })

  it('with_page_keeps_filters', () => {
    const silver = withFilters(initialTenantQuery, { ...noFilters, tier: 'Silver' })

    expect(withPage(silver, 2)).toEqual({ filters: silver.filters, page: 2 })
    expect(withPage(silver, 0)).toBe(silver)
  })

  it('same_query_compares_filters_and_page', () => {
    const over = withFilters(initialTenantQuery, { ...noFilters, status: 'over' })

    expect(sameQuery(over, { filters: { ...noFilters, status: 'over' }, page: 0 })).toBe(true)
    expect(sameQuery(over, withPage(over, 1))).toBe(false)
    expect(sameQuery(over, initialTenantQuery)).toBe(false)
    expect(withFilters(initialTenantQuery, { ...noFilters })).toBe(initialTenantQuery)
  })

  it('unchanged_filters_keep_page', () => {
    const onSecondPage = withPage(initialTenantQuery, 1)

    expect(withFilters(onSecondPage, { ...noFilters })).toBe(onSecondPage)
  })
})
