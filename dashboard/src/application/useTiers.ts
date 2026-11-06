import { useEffect, useState } from 'react'
import type { TierOption } from '../domain/tiers.ts'
import { useDashboardPorts } from './dashboardPortsContext.ts'
import { startPolling, type Stop } from './polling.ts'

export const tierListRetryMillis = 5_000
export const tierListTimeoutMillis = 2_000

export function useTiers(): readonly TierOption[] | null {
  const { tierAdministration, scheduler } = useDashboardPorts()
  const [tiers, setTiers] = useState<readonly TierOption[] | null>(null)

  useEffect(() => {
    let stopLoading: Stop = () => {}
    stopLoading = startPolling<readonly TierOption[]>({
      task: (signal) => tierAdministration.fetchTiers(signal),
      intervalMs: tierListRetryMillis,
      timeoutMs: tierListTimeoutMillis,
      scheduler,
      onSuccess: (loaded) => {
        stopLoading()
        setTiers(loaded)
      },
      onFailure: () => {},
    })
    return () => stopLoading()
  }, [tierAdministration, scheduler])

  return tiers
}
