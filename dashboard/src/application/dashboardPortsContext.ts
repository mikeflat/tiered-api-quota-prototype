import { createContext, useContext } from 'react'
import type { HealthSource, Scheduler, SnapshotSource, TierAdministration } from './ports.ts'

export interface DashboardPorts {
  readonly snapshots: SnapshotSource
  readonly health: HealthSource
  readonly scheduler: Scheduler
  readonly tierAdministration: TierAdministration
}

export const DashboardPortsContext = createContext<DashboardPorts | null>(null)

export function useDashboardPorts(): DashboardPorts {
  const ports = useContext(DashboardPortsContext)
  if (ports === null) {
    throw new Error('useDashboardPorts must be used inside DashboardPortsProvider')
  }
  return ports
}
