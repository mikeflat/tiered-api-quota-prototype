import { describe, expect, it } from 'vitest'
import { snapshotWith } from '../support/snapshots.ts'
import { type ConnectionState, initialConnectionState, nextConnectionState } from '../../src/domain/connection.ts'
import { initialTenantQuery } from '../../src/domain/tenantQuery.ts'

const first = snapshotWith()
const second = snapshotWith({ period: { index: 100_001, endsAt: '1970-03-11T10:42:00Z', secondsLeft: 59 } })

function received(state: ConnectionState, at: number, snapshot = first): ConnectionState {
  return nextConnectionState(state, { kind: 'snapshotReceived', snapshot, query: initialTenantQuery, at })
}

function failed(state: ConnectionState, at: number): ConnectionState {
  return nextConnectionState(state, { kind: 'snapshotFailed', at })
}

describe('nextConnectionState', () => {
  it('first_snapshot_makes_state_live', () => {
    expect(received(initialConnectionState, 1000)).toEqual({
      kind: 'live',
      latest: { snapshot: first, query: initialTenantQuery, receivedAt: 1000 },
      consecutiveFailures: 0,
      reconnectedAt: null,
    })
  })

  it('one_failure_keeps_live', () => {
    const state = failed(received(initialConnectionState, 1000), 2000)

    expect(state).toMatchObject({ kind: 'live', consecutiveFailures: 1, latest: { snapshot: first } })
  })

  it('two_failures_make_live_unreachable_with_last_snapshot', () => {
    const state = failed(failed(received(initialConnectionState, 1000), 2000), 3000)

    expect(state).toEqual({ kind: 'unreachable', last: { snapshot: first, query: initialTenantQuery, receivedAt: 1000 } })
  })

  it('two_failures_while_loading_make_unreachable_without_data', () => {
    const afterOne = failed(initialConnectionState, 1000)
    expect(afterOne).toEqual({ kind: 'loading', consecutiveFailures: 1 })

    expect(failed(afterOne, 2000)).toEqual({ kind: 'unreachable', last: null })
  })

  it('success_after_unreachable_sets_reconnected_at', () => {
    const unreachable = failed(failed(received(initialConnectionState, 1000), 2000), 3000)

    expect(received(unreachable, 4000, second)).toEqual({
      kind: 'live',
      latest: { snapshot: second, query: initialTenantQuery, receivedAt: 4000 },
      consecutiveFailures: 0,
      reconnectedAt: 4000,
    })
  })

  it('success_after_single_failure_does_not_set_reconnected_at', () => {
    const state = received(failed(received(initialConnectionState, 1000), 2000), 3000, second)

    expect(state).toMatchObject({ kind: 'live', consecutiveFailures: 0, reconnectedAt: null })
  })

  it('failures_while_unreachable_keep_last_snapshot', () => {
    const unreachable = failed(failed(received(initialConnectionState, 1000), 2000), 3000)

    expect(failed(failed(unreachable, 4000), 5000)).toEqual({
      kind: 'unreachable',
      last: { snapshot: first, query: initialTenantQuery, receivedAt: 1000 },
    })
  })

  it('later_snapshots_keep_reconnected_at', () => {
    const reconnected = received(failed(failed(received(initialConnectionState, 1000), 2000), 3000), 4000)

    expect(received(reconnected, 5000, second)).toMatchObject({ reconnectedAt: 4000 })
  })

  it('received_snapshot_keeps_its_query', () => {
    const goldQuery = { ...initialTenantQuery, filters: { ...initialTenantQuery.filters, tier: 'Gold' } }

    const state = nextConnectionState(initialConnectionState, {
      kind: 'snapshotReceived',
      snapshot: snapshotWith(),
      query: goldQuery,
      at: 1000,
    })

    expect(state.kind === 'live' && state.latest.query).toBe(goldQuery)
  })
})
