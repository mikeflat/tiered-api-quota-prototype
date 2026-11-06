import type { ConnectionStatus } from '../domain/dashboardView.ts'
import type { EnforcementStatus } from '../domain/enforcement.ts'
import type { TenantStatus } from '../domain/snapshot.ts'
import type { TierChangeFailure } from '../domain/tierChange.ts'
import type { TierOption } from '../domain/tiers.ts'

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

const tenantStatusLabels: Record<TenantStatus, StatusLabel> = {
  ok: { text: 'OK', tone: 'ok' },
  near: { text: 'Near', tone: 'warning' },
  over: { text: 'Over', tone: 'error' },
}

export function tenantStatusLabelOf(status: TenantStatus | null): StatusLabel {
  return status === null ? { text: missingValue, tone: 'neutral' } : tenantStatusLabels[status]
}

export function formatUsage(used: number | null, limit: number, percent: number | null): string {
  if (used === null || percent === null) {
    return missingValue
  }
  return `${formatCount(used)} / ${formatCount(limit)} (${percent}%)`
}

export function formatOptionalCount(value: number | null): string {
  return value === null ? missingValue : formatCount(value)
}

export function tierOptionLabel(tier: TierOption): string {
  return `${tier.name} (${formatCount(tier.limit)})`
}

export function tierChangeFailureText(failure: TierChangeFailure): string {
  return `Tier change failed: ${tierChangeFailureReason(failure)}`
}

function tierChangeFailureReason(failure: TierChangeFailure): string {
  switch (failure.kind) {
    case 'notFound':
      return 'tenant not found'
    case 'rejected':
      return 'tier rejected by the gateway'
    case 'serverError':
      return `gateway error (${failure.status})`
    case 'timedOut':
      return 'no answer within 2 s'
    case 'unreachable':
      return 'gateway unreachable'
  }
}
