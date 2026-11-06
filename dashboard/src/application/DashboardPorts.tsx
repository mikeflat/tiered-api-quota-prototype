import type { ReactNode } from 'react'
import { type DashboardPorts, DashboardPortsContext } from './dashboardPortsContext.ts'

export function DashboardPortsProvider({ ports, children }: { ports: DashboardPorts; children: ReactNode }) {
  return <DashboardPortsContext value={ports}>{children}</DashboardPortsContext>
}
