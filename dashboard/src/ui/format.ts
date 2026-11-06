import type { ConnectionStatus } from '../domain/dashboardView.ts'
import type { EnforcementStatus } from '../domain/enforcement.ts'

export const missingValue = '—'

export type StatusTone = 'neutral' | 'ok' | 'warning' | 'error'

export interface StatusLabel {
  readonly text: string
  readonly tone: StatusTone
}

const counts = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

export function formatCount(value: number): string {
  return counts.format(value)
}

export function formatCountdown(seconds: number | null): string {
  if (seconds === null) {
    return missingValue
  }
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainingSeconds = seconds % 60
  return hours > 0
    ? `${hours}:${twoDigits(minutes)}:${twoDigits(remainingSeconds)}`
    : `${minutes}:${twoDigits(remainingSeconds)}`
}

function twoDigits(value: number): string {
  return value.toString().padStart(2, '0')
}

const connectionLabels: Record<ConnectionStatus, StatusLabel> = {
  connecting: { text: 'Connecting', tone: 'neutral' },
  connected: { text: 'Connected', tone: 'ok' },
  unreachable: { text: 'Unreachable', tone: 'error' },
}

const enforcementLabels: Record<EnforcementStatus, StatusLabel> = {
  unknown: { text: 'Unknown', tone: 'neutral' },
  ok: { text: 'OK', tone: 'ok' },
  degraded: { text: 'Degraded', tone: 'warning' },
}

export function connectionLabelOf(status: ConnectionStatus): StatusLabel {
  return connectionLabels[status]
}

export function enforcementLabelOf(status: EnforcementStatus): StatusLabel {
  return enforcementLabels[status]
}
