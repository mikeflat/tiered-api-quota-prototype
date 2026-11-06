import { useEffect } from 'react'
import type { DashboardView } from '../domain/dashboardView.ts'
import { type FilterControlsView, filterControlsOf } from '../domain/filterControls.ts'
import { correctedPageOf } from '../domain/pagination.ts'
import type { TenantGridView } from '../domain/tenantGrid.ts'
import { sameQuery } from '../domain/tenantQuery.ts'
import { useDashboard } from './useDashboard.ts'
import { useTenantGrid } from './useTenantGrid.ts'
import { type TenantQueryActions, useTenantQuery } from './useTenantQuery.ts'
import { useTiers } from './useTiers.ts'

export interface DashboardScreenModel {
  readonly view: DashboardView
  readonly grid: TenantGridView | null
  readonly filters: FilterControlsView
  readonly tenantActions: TenantQueryActions
  readonly changeTier: (tenantId: number, tier: string) => void
}

export function useDashboardScreen(): DashboardScreenModel {
  const { query, drafts, actions } = useTenantQuery()
  const view = useDashboard(query)
  const tiers = useTiers()
  const { grid, changeTier } = useTenantGrid(view, tiers, query)
  const shownPage = view.snapshot?.tenants ?? null
  const shownQuery = view.shownQuery

  useEffect(() => {
    if (shownPage === null || shownQuery === null || !sameQuery(shownQuery, query)) {
      return
    }
    const corrected = correctedPageOf(shownPage)
    if (corrected !== null) {
      actions.goToPage(corrected)
    }
  }, [shownPage, shownQuery, query, actions])

  return {
    view,
    grid,
    filters: filterControlsOf(drafts, query, tiers, view.tierControlsEnabled, view.metricsAvailable),
    tenantActions: actions,
    changeTier,
  }
}
