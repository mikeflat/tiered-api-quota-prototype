import type { ConnectionState, ReceivedSnapshot } from './connection.ts'
import type { EnforcementStatus } from './enforcement.ts'
import { secondsUntilReset } from './resetCountdown.ts'
import type { AdminSnapshot } from './snapshot.ts'

export const reconnectedNoticeMillis = 3000

export type ConnectionStatus = 'connecting' | 'connected' | 'unreachable'

export interface DashboardView {
  readonly loading: boolean
  readonly gatewayUnreachable: boolean
  readonly snapshot: AdminSnapshot | null
  readonly stale: boolean
  readonly lastUpdatedSecondsAgo: number | null
  readonly reconnectedNoticeVisible: boolean
  readonly enforcementDegraded: boolean
  readonly metricsAvailable: boolean
  readonly tierControlsEnabled: boolean
  readonly connectionStatus: ConnectionStatus
  readonly enforcement: EnforcementStatus
  readonly secondsUntilReset: number | null
}

export function dashboardViewOf(
  connection: ConnectionState,
  enforcement: EnforcementStatus,
  now: number,
): DashboardView {
  const shown = shownSnapshot(connection)
  const stale = connection.kind === 'unreachable' && shown !== null
  return {
    loading: shown === null,
    gatewayUnreachable: connection.kind === 'unreachable',
    snapshot: shown?.snapshot ?? null,
    stale,
    lastUpdatedSecondsAgo: stale ? wholeSecondsBetween(shown.receivedAt, now) : null,
    reconnectedNoticeVisible: isReconnectedNoticeVisible(connection, now),
    enforcementDegraded: enforcement === 'degraded',
    metricsAvailable: shown !== null && shown.snapshot.metrics !== null,
    tierControlsEnabled: connection.kind === 'live',
    connectionStatus: connectionStatusOf(connection),
    enforcement,
    secondsUntilReset: resetCountdownOf(connection, now),
  }
}

function connectionStatusOf(connection: ConnectionState): ConnectionStatus {
  switch (connection.kind) {
    case 'loading':
      return 'connecting'
    case 'live':
      return 'connected'
    case 'unreachable':
      return 'unreachable'
  }
}

function resetCountdownOf(connection: ConnectionState, now: number): number | null {
  if (connection.kind !== 'live') {
    return null
  }
  const { snapshot, receivedAt } = connection.latest
  return secondsUntilReset(snapshot.period, receivedAt, now)
}

function shownSnapshot(connection: ConnectionState): ReceivedSnapshot | null {
  switch (connection.kind) {
    case 'loading':
      return null
    case 'live':
      return connection.latest
    case 'unreachable':
      return connection.last
  }
}

function wholeSecondsBetween(earlier: number, later: number): number {
  return Math.max(0, Math.floor((later - earlier) / 1000))
}

function isReconnectedNoticeVisible(connection: ConnectionState, now: number): boolean {
  return (
    connection.kind === 'live' &&
    connection.reconnectedAt !== null &&
    now - connection.reconnectedAt < reconnectedNoticeMillis
  )
}
