import { useEffect, useReducer, useRef } from 'react'
import type { DashboardView } from '../domain/dashboardView.ts'
import { tenantGridOf, type TenantGridView } from '../domain/tenantGrid.ts'
import { nextTierChanges, noTierChanges } from '../domain/tierChange.ts'
import { useDashboardPorts } from './dashboardPortsContext.ts'
import { requestWithTimeout } from './requestWithTimeout.ts'
import { tierChangeFailureOf } from './tierChangeFailure.ts'
import { useTiers } from './useTiers.ts'

export const tierChangeTimeoutMillis = 2_000

export interface TenantGrid {
  readonly grid: TenantGridView | null
  readonly changeTier: (tenantId: number, tier: string) => void
}

export function useTenantGrid(view: DashboardView): TenantGrid {
  const { tierAdministration, scheduler } = useDashboardPorts()
  const tiers = useTiers()
  const [changes, dispatch] = useReducer(nextTierChanges, noTierChanges)
  const changesInFlight = useRef(new Set<AbortController>())
  const page = view.snapshot?.tenants ?? null

  useEffect(() => {
    if (page?.rows) {
      dispatch({ kind: 'rowsReceived', rows: page.rows })
    }
  }, [page])

  useEffect(() => {
    const inFlight = changesInFlight.current
    return () => {
      for (const controller of inFlight) {
        controller.abort()
      }
      inFlight.clear()
    }
  }, [])

  const grid = page === null ? null : tenantGridOf(page, changes, tiers, view.tierControlsEnabled)

  const changeTier = (tenantId: number, tier: string) => {
    const row = grid?.kind === 'rows' ? grid.rows.find((candidate) => candidate.id === tenantId) : undefined
    if (row === undefined || !row.selectorEnabled || row.tier === tier) {
      return
    }
    const controller = new AbortController()
    changesInFlight.current.add(controller)
    dispatch({ kind: 'requested', tenantId, tier })
    requestWithTimeout(
      (signal) => tierAdministration.changeTier(tenantId, tier, signal),
      tierChangeTimeoutMillis,
      scheduler,
      controller.signal,
    )
      .then(
        (changed) => {
          if (!controller.signal.aborted) {
            dispatch({ kind: 'succeeded', tenantId, changed })
          }
        },
        (error: unknown) => {
          if (!controller.signal.aborted) {
            dispatch({ kind: 'failed', tenantId, failure: tierChangeFailureOf(error) })
          }
        },
      )
      .finally(() => changesInFlight.current.delete(controller))
  }

  return { grid, changeTier }
}
