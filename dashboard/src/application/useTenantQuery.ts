import { useEffect, useMemo, useRef, useState } from 'react'
import { emptyDrafts, type FilterDrafts } from '../domain/filterControls.ts'
import { parseIdInput } from '../domain/idInput.ts'
import type { TenantStatus } from '../domain/snapshot.ts'
import {
  initialTenantQuery,
  noFilters,
  type TenantFilters,
  type TenantQuery,
  withFilters,
  withPage,
} from '../domain/tenantQuery.ts'
import { useDashboardPorts } from './dashboardPortsContext.ts'
import type { Cancel } from './ports.ts'

export const typedFilterPauseMillis = 300

export interface TenantQueryActions {
  readonly setNameText: (text: string) => void
  readonly setIdText: (text: string) => void
  readonly setTier: (tier: string | null) => void
  readonly setStatus: (status: TenantStatus | null) => void
  readonly clearFilters: () => void
  readonly clearStatus: () => void
  readonly nextPage: () => void
  readonly previousPage: () => void
  readonly goToPage: (page: number) => void
}

export interface TenantQueryState {
  readonly query: TenantQuery
  readonly drafts: FilterDrafts
  readonly actions: TenantQueryActions
}

export function useTenantQuery(): TenantQueryState {
  const { scheduler } = useDashboardPorts()
  const [query, setQuery] = useState<TenantQuery>(initialTenantQuery)
  const [drafts, setDrafts] = useState<FilterDrafts>(emptyDrafts)
  const draftsRef = useRef<FilterDrafts>(emptyDrafts)
  const cancelPendingApply = useRef<Cancel | null>(null)

  useEffect(() => () => cancelPendingApply.current?.(), [])

  const actions = useMemo<TenantQueryActions>(() => {
    const cancelPending = () => {
      cancelPendingApply.current?.()
      cancelPendingApply.current = null
    }

    const applyFilters = (change: (filters: TenantFilters) => TenantFilters) => {
      cancelPending()
      setQuery((current) => withFilters(current, change(filtersWithDrafts(current.filters, draftsRef.current))))
    }

    const updateDrafts = (next: FilterDrafts) => {
      draftsRef.current = next
      setDrafts(next)
      cancelPending()
      cancelPendingApply.current = scheduler.schedule(() => {
        cancelPendingApply.current = null
        setQuery((current) => withFilters(current, filtersWithDrafts(current.filters, draftsRef.current)))
      }, typedFilterPauseMillis)
    }

    return {
      setNameText: (text) => updateDrafts({ ...draftsRef.current, nameText: text }),
      setIdText: (text) => updateDrafts({ ...draftsRef.current, idText: text }),
      setTier: (tier) => applyFilters((filters) => ({ ...filters, tier })),
      setStatus: (status) => applyFilters((filters) => ({ ...filters, status })),
      clearStatus: () => applyFilters((filters) => ({ ...filters, status: null })),
      clearFilters: () => {
        cancelPending()
        draftsRef.current = emptyDrafts
        setDrafts(emptyDrafts)
        setQuery((current) => withFilters(current, noFilters))
      },
      nextPage: () => setQuery((current) => withPage(current, current.page + 1)),
      previousPage: () => setQuery((current) => withPage(current, Math.max(0, current.page - 1))),
      goToPage: (page) => setQuery((current) => withPage(current, page)),
    }
  }, [scheduler])

  return { query, drafts, actions }
}

function filtersWithDrafts(filters: TenantFilters, drafts: FilterDrafts): TenantFilters {
  return { ...filters, name: drafts.nameText.trim(), id: idFromDraft(drafts.idText, filters.id) }
}

function idFromDraft(text: string, appliedId: number | null): number | null {
  const parsed = parseIdInput(text)
  switch (parsed.kind) {
    case 'empty':
      return null
    case 'valid':
      return parsed.id
    case 'invalid':
      return appliedId
  }
}
