import { useCallback, useReducer, useState } from 'react'
import { initialConnectionState, nextConnectionState } from '../domain/connection.ts'
import { type DashboardView, dashboardViewOf } from '../domain/dashboardView.ts'
import { type EnforcementStatus, enforcementStatusOf, type HealthReport } from '../domain/enforcement.ts'
import type { AdminSnapshot } from '../domain/snapshot.ts'
import type { TenantQuery } from '../domain/tenantQuery.ts'
import { useDashboardPorts } from './dashboardPortsContext.ts'
import { useNow } from './useNow.ts'
import { usePolling } from './usePolling.ts'

export const snapshotPollMillis = 1_000
export const healthPollMillis = 5_000
export const pollTimeoutMillis = 2_000
export const clockTickMillis = 1_000

interface AnsweredSnapshot {
  readonly snapshot: AdminSnapshot
  readonly query: TenantQuery
}

export function useDashboard(query: TenantQuery): DashboardView {
  const { snapshots, health, scheduler } = useDashboardPorts()
  const [connection, dispatch] = useReducer(nextConnectionState, initialConnectionState)
  const [enforcement, setEnforcement] = useState<EnforcementStatus>('unknown')
  const now = useNow(scheduler, clockTickMillis)

  const fetchSnapshot = useCallback(
    async (signal: AbortSignal): Promise<AnsweredSnapshot> => ({
      snapshot: await snapshots.fetchSnapshot(query, signal),
      query,
    }),
    [snapshots, query],
  )
  const fetchHealth = useCallback((signal: AbortSignal) => health.fetchHealth(signal), [health])

  usePolling<AnsweredSnapshot>({
    task: fetchSnapshot,
    intervalMs: snapshotPollMillis,
    timeoutMs: pollTimeoutMillis,
    scheduler,
    onSuccess: (answered) => dispatch({ kind: 'snapshotReceived', ...answered, at: scheduler.now() }),
    onFailure: () => dispatch({ kind: 'snapshotFailed', at: scheduler.now() }),
  })

  usePolling<HealthReport>({
    task: fetchHealth,
    intervalMs: healthPollMillis,
    timeoutMs: pollTimeoutMillis,
    scheduler,
    onSuccess: (report) => setEnforcement(enforcementStatusOf(report)),
    onFailure: () => setEnforcement('unknown'),
  })

  return dashboardViewOf(connection, enforcement, now)
}

