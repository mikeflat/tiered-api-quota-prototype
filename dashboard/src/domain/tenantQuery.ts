import type { TenantStatus } from './snapshot.ts'

export const tenantPageSize = 10

export interface TenantFilters {
  readonly name: string
  readonly id: number | null
  readonly tier: string | null
  readonly status: TenantStatus | null
}

export interface TenantQuery {
  readonly filters: TenantFilters
  readonly page: number
}

export const noFilters: TenantFilters = { name: '', id: null, tier: null, status: null }

export const initialTenantQuery: TenantQuery = { filters: noFilters, page: 0 }

export function withFilters(query: TenantQuery, filters: TenantFilters): TenantQuery {
  return sameFilters(query.filters, filters) ? query : { filters, page: 0 }
}

export function withPage(query: TenantQuery, page: number): TenantQuery {
  return query.page === page ? query : { ...query, page }
}

export function sameQuery(a: TenantQuery, b: TenantQuery): boolean {
  return a.page === b.page && sameFilters(a.filters, b.filters)
}

function sameFilters(a: TenantFilters, b: TenantFilters): boolean {
  return a.name === b.name && a.id === b.id && a.tier === b.tier && a.status === b.status
}
