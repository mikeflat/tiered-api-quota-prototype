import type { AdminSnapshot } from './snapshot.ts'
import type { TenantQuery } from './tenantQuery.ts'

export const failuresBeforeUnreachable = 2

export interface ReceivedSnapshot {
  readonly snapshot: AdminSnapshot
  readonly query: TenantQuery
  readonly receivedAt: number
}

export interface Loading {
  readonly kind: 'loading'
  readonly consecutiveFailures: number
}

export interface Live {
  readonly kind: 'live'
  readonly latest: ReceivedSnapshot
  readonly consecutiveFailures: number
  readonly reconnectedAt: number | null
}

export interface Unreachable {
  readonly kind: 'unreachable'
  readonly last: ReceivedSnapshot | null
}

export type ConnectionState = Loading | Live | Unreachable

export type ConnectionEvent =
  | {
      readonly kind: 'snapshotReceived'
      readonly snapshot: AdminSnapshot
      readonly query: TenantQuery
      readonly at: number
    }
  | { readonly kind: 'snapshotFailed'; readonly at: number }

export const initialConnectionState: ConnectionState = { kind: 'loading', consecutiveFailures: 0 }

export function nextConnectionState(state: ConnectionState, event: ConnectionEvent): ConnectionState {
  if (event.kind === 'snapshotReceived') {
    return afterSnapshotReceived(state, { snapshot: event.snapshot, query: event.query, receivedAt: event.at })
  }
  return afterSnapshotFailed(state)
}

function afterSnapshotReceived(state: ConnectionState, received: ReceivedSnapshot): Live {
  return {
    kind: 'live',
    latest: received,
    consecutiveFailures: 0,
    reconnectedAt: reconnectedAtAfter(state, received.receivedAt),
  }
}

function reconnectedAtAfter(state: ConnectionState, receivedAt: number): number | null {
  switch (state.kind) {
    case 'unreachable':
      return receivedAt
    case 'live':
      return state.reconnectedAt
    case 'loading':
      return null
  }
}

function afterSnapshotFailed(state: ConnectionState): ConnectionState {
  switch (state.kind) {
    case 'unreachable':
      return state
    case 'loading': {
      const failures = state.consecutiveFailures + 1
      return failures < failuresBeforeUnreachable
        ? { kind: 'loading', consecutiveFailures: failures }
        : { kind: 'unreachable', last: null }
    }
    case 'live': {
      const failures = state.consecutiveFailures + 1
      return failures < failuresBeforeUnreachable
        ? { ...state, consecutiveFailures: failures }
        : { kind: 'unreachable', last: state.latest }
    }
  }
}
