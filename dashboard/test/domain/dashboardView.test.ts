import { describe, expect, it } from 'vitest'
import { snapshotWith, snapshotWithoutMetrics } from '../support/snapshots.ts'
import type { ConnectionState } from '../../src/domain/connection.ts'
import { dashboardViewOf } from '../../src/domain/dashboardView.ts'
import { initialTenantQuery } from '../../src/domain/tenantQuery.ts'

const snapshot = snapshotWith()

const loading: ConnectionState = { kind: 'loading', consecutiveFailures: 0 }
const live: ConnectionState = {
  kind: 'live',
  latest: { snapshot, query: initialTenantQuery, receivedAt: 10_000 },
  consecutiveFailures: 0,
  reconnectedAt: null,
}
const unreachableWithData: ConnectionState = { kind: 'unreachable', last: { snapshot, query: initialTenantQuery, receivedAt: 10_000 } }
const unreachableWithoutData: ConnectionState = { kind: 'unreachable', last: null }

describe('dashboardViewOf', () => {
  it('loading_has_no_snapshot_and_disabled_controls', () => {
    expect(dashboardViewOf(loading, 'unknown', 0)).toEqual({
      loading: true,
      gatewayUnreachable: false,
      snapshot: null,
      stale: false,
      lastUpdatedSecondsAgo: null,
      reconnectedNoticeVisible: false,
      enforcementDegraded: false,
      metricsAvailable: false,
      tierControlsEnabled: false,
      connectionStatus: 'connecting',
      enforcement: 'unknown',
      secondsUntilReset: null,
      shownQuery: null,
    })
  })

  it('live_view_shows_snapshot_with_enabled_controls', () => {
    expect(dashboardViewOf(live, 'ok', 10_500)).toMatchObject({
      loading: false,
      gatewayUnreachable: false,
      snapshot,
      stale: false,
      metricsAvailable: true,
      tierControlsEnabled: true,
    })
  })

  it('stale_view_reports_whole_seconds_since_last_update', () => {
    expect(dashboardViewOf(unreachableWithData, 'unknown', 17_900)).toMatchObject({
      loading: false,
      gatewayUnreachable: true,
      snapshot,
      stale: true,
      lastUpdatedSecondsAgo: 7,
    })
  })

  it('unreachable_without_data_keeps_loading', () => {
    expect(dashboardViewOf(unreachableWithoutData, 'unknown', 5000)).toMatchObject({
      loading: true,
      gatewayUnreachable: true,
      snapshot: null,
      stale: false,
      lastUpdatedSecondsAgo: null,
    })
  })

  it('reconnected_notice_visible_for_three_seconds', () => {
    const reconnected: ConnectionState = { ...live, reconnectedAt: 10_000 }

    expect(dashboardViewOf(reconnected, 'ok', 10_000).reconnectedNoticeVisible).toBe(true)
    expect(dashboardViewOf(reconnected, 'ok', 12_999).reconnectedNoticeVisible).toBe(true)
    expect(dashboardViewOf(reconnected, 'ok', 13_000).reconnectedNoticeVisible).toBe(false)
  })

  it('null_metrics_mark_metrics_unavailable_and_keep_controls_enabled', () => {
    const withoutMetrics: ConnectionState = {
      ...live,
      latest: { snapshot: snapshotWithoutMetrics(), query: initialTenantQuery, receivedAt: 10_000 },
    }

    expect(dashboardViewOf(withoutMetrics, 'degraded', 10_000)).toMatchObject({
      metricsAvailable: false,
      tierControlsEnabled: true,
    })
  })

  it('degraded_enforcement_is_reported_independently', () => {
    expect(dashboardViewOf(loading, 'degraded', 0).enforcementDegraded).toBe(true)
    expect(dashboardViewOf(live, 'degraded', 10_000).enforcementDegraded).toBe(true)
    expect(dashboardViewOf(live, 'ok', 10_000).enforcementDegraded).toBe(false)
    expect(dashboardViewOf(live, 'unknown', 10_000).enforcementDegraded).toBe(false)
  })

  it('unreachable_disables_tier_controls', () => {
    expect(dashboardViewOf(unreachableWithData, 'ok', 11_000).tierControlsEnabled).toBe(false)
  })

  it('loading_is_connecting_without_countdown', () => {
    expect(dashboardViewOf(loading, 'ok', 0)).toMatchObject({ connectionStatus: 'connecting', secondsUntilReset: null })
    expect(dashboardViewOf(unreachableWithoutData, 'ok', 0)).toMatchObject({
      connectionStatus: 'unreachable',
      secondsUntilReset: null,
    })
  })

  it('live_is_connected_with_countdown', () => {
    expect(dashboardViewOf(live, 'ok', 10_000)).toMatchObject({ connectionStatus: 'connected', secondsUntilReset: 15 })
  })

  it('one_failure_stays_connected', () => {
    const oneFailure: ConnectionState = { ...live, consecutiveFailures: 1 }

    expect(dashboardViewOf(oneFailure, 'ok', 11_000)).toMatchObject({
      connectionStatus: 'connected',
      secondsUntilReset: 14,
    })
  })

  it('unreachable_has_no_countdown', () => {
    expect(dashboardViewOf(unreachableWithData, 'ok', 11_000)).toMatchObject({
      connectionStatus: 'unreachable',
      secondsUntilReset: null,
    })
  })

  it('enforcement_status_is_passed_through', () => {
    expect(dashboardViewOf(live, 'ok', 10_000).enforcement).toBe('ok')
    expect(dashboardViewOf(live, 'degraded', 10_000).enforcement).toBe('degraded')
    expect(dashboardViewOf(loading, 'unknown', 0).enforcement).toBe('unknown')
  })

  it('countdown_counts_down_with_now', () => {
    expect(dashboardViewOf(live, 'ok', 12_000).secondsUntilReset).toBe(13)
    expect(dashboardViewOf(live, 'ok', 40_000).secondsUntilReset).toBe(0)
  })

  it('view_exposes_shown_query', () => {
    expect(dashboardViewOf(live, 'ok', 10_000).shownQuery).toBe(initialTenantQuery)
    expect(dashboardViewOf(unreachableWithData, 'ok', 10_000).shownQuery).toBe(initialTenantQuery)
    expect(dashboardViewOf(loading, 'ok', 0).shownQuery).toBeNull()
  })
})
