import { vi } from 'vitest'
import type { TenantQueryActions } from '../../src/application/useTenantQuery.ts'

export function recordingActions() {
  return {
    setNameText: vi.fn<TenantQueryActions['setNameText']>(),
    setIdText: vi.fn<TenantQueryActions['setIdText']>(),
    setTier: vi.fn<TenantQueryActions['setTier']>(),
    setStatus: vi.fn<TenantQueryActions['setStatus']>(),
    clearFilters: vi.fn<TenantQueryActions['clearFilters']>(),
    clearStatus: vi.fn<TenantQueryActions['clearStatus']>(),
    nextPage: vi.fn<TenantQueryActions['nextPage']>(),
    previousPage: vi.fn<TenantQueryActions['previousPage']>(),
    goToPage: vi.fn<TenantQueryActions['goToPage']>(),
  } satisfies TenantQueryActions
}
