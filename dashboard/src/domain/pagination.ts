import type { TenantPage } from './snapshot.ts'

export function pageCountOf(total: number, size: number): number {
  return Math.max(1, Math.ceil(total / size))
}

export function correctedPageOf(page: TenantPage): number | null {
  if (!page.rowsAvailable || page.total === null) {
    return null
  }
  const lastPage = pageCountOf(page.total, page.size) - 1
  return page.page > lastPage ? lastPage : null
}
