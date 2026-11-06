import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DashboardPortsProvider } from '../../src/application/DashboardPorts.tsx'
import { flushPromises } from '../support/deferred.ts'
import { FakeHealthSource, FakeSnapshotSource, redisDown, redisUp } from '../support/fakeSources.ts'
import { FakeTierAdministration } from '../support/fakeTierAdministration.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'
import { metricsWith, minuteOfTraffic, snapshotWith, snapshotWithoutMetrics } from '../support/snapshots.ts'
import type { AdminSnapshot, TenantRow } from '../../src/domain/snapshot.ts'
import { TierChangeRefused } from '../../src/domain/tierChange.ts'
import { changedTier, defaultTiers, tenantRow } from '../support/tiers.ts'
import App from '../../src/ui/App.tsx'

function renderApp() {
  const scheduler = new ManualScheduler(1_700_000_000_000)
  const snapshots = new FakeSnapshotSource()
  const health = new FakeHealthSource()
  const tierAdministration = new FakeTierAdministration()
  render(
    <DashboardPortsProvider ports={{ snapshots, health, scheduler, tierAdministration }}>
      <App />
    </DashboardPortsProvider>,
  )
  return { scheduler, snapshots, health, tierAdministration }
}

type Harness = ReturnType<typeof renderApp>

async function settle(action: () => void): Promise<void> {
  await act(async () => {
    action()
    await flushPromises()
  })
}

async function nextSecond(harness: Harness): Promise<void> {
  await settle(() => harness.scheduler.advanceBy(1_000))
}

async function snapshotSucceeds(harness: Harness, snapshot = snapshotWith()): Promise<void> {
  await settle(() => harness.snapshots.answerLatest(snapshot))
}

async function snapshotFails(harness: Harness): Promise<void> {
  await settle(() => harness.snapshots.failLatest())
}

async function liveThenUnreachable(harness: Harness): Promise<void> {
  await snapshotSucceeds(harness)
  await nextSecond(harness)
  await snapshotFails(harness)
  await nextSecond(harness)
  await snapshotFails(harness)
}

function unreachableBanner(): HTMLElement | null {
  return screen.queryByText('Gateway unreachable')
}

function degradedBanner(): HTMLElement | null {
  return screen.queryByText('Quota enforcement degraded: no new blocks, counting paused')
}

function reconnectedNotice(): HTMLElement | null {
  return screen.queryByText('Reconnected')
}

function loadingIndicator(): HTMLElement | null {
  return screen.queryByText(/Loading dashboard/)
}

function header(): HTMLElement {
  return screen.getByRole('banner')
}

function snapshotWithSecondsLeft(secondsLeft: number, index = 100_000) {
  return snapshotWith({ period: { index, endsAt: '1970-03-11T10:41:00Z', secondsLeft } })
}

function snapshotWithTraffic(newestAllowed: number, totalAllowed: number) {
  return snapshotWith({
    metrics: metricsWith({
      perSecond: minuteOfTraffic(6_000_044, (secondsAgo) =>
        secondsAgo === 0 ? { allowed: newestAllowed, rejected: 4 } : { allowed: 50, rejected: 0 },
      ),
      totals: { allowed: totalAllowed, rejected: 731 },
    }),
  })
}

const seedNames = [
  'Acme Corp',
  'Blue Bank',
  'Cobalt Labs',
  'Delta Air',
  'Echo Media',
  'Fjord Foods',
  'Granite Works',
  'Harbor Health',
  'Iris Retail',
  'Juniper Energy',
]

function firstPage(overrides: (row: TenantRow) => Partial<TenantRow> = () => ({})): AdminSnapshot {
  const rows = seedNames.map((name, index) => {
    const row = tenantRow({ id: index + 1, name, used: 10 * index, rejected: index, status: 'ok' })
    return { ...row, ...overrides(row) }
  })
  return snapshotWith({ tenants: { rowsAvailable: true, rows, total: 15, page: 0, size: 10 } })
}

function acmeAs(tier: string, limit: number, used = 0): AdminSnapshot {
  return firstPage((row) => (row.id === 1 ? { tier, limit, used } : {}))
}

function selectorOf(name: string): HTMLSelectElement {
  return screen.getByRole('combobox', { name: `Tier for ${name}` })
}

function rowOf(name: string): HTMLElement {
  const row = screen.getByRole('rowheader', { name }).closest('tr')
  if (row === null) {
    throw new Error(`no row for ${name}`)
  }
  return row
}

async function chooseTier(name: string, tier: string): Promise<void> {
  await settle(() => fireEvent.change(selectorOf(name), { target: { value: tier } }))
}

async function liveWithTiers(harness: Harness, snapshot = firstPage()): Promise<void> {
  await settle(() => harness.tierAdministration.answerTiers(defaultTiers))
  await snapshotSucceeds(harness, snapshot)
}

function region(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

describe('App', () => {
  it('shows_heading_and_loading_until_first_snapshot', () => {
    renderApp()

    expect(screen.getByRole('heading', { level: 1, name: 'Quota dashboard' })).toBeInTheDocument()
    expect(loadingIndicator()).toBeInTheDocument()
    expect(screen.queryByRole('region')).not.toBeInTheDocument()
    expect(screen.getByRole('main')).not.toHaveTextContent(/\d/)
  })

  it('first_snapshot_shows_regions', async () => {
    const harness = renderApp()

    await snapshotSucceeds(harness)

    expect(loadingIndicator()).not.toBeInTheDocument()
    expect(region('Traffic')).toBeInTheDocument()
    expect(region('Period totals')).toBeInTheDocument()
    expect(region('Tenants')).toBeInTheDocument()
  })

  it('one_failed_poll_shows_no_banner', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness)
    await nextSecond(harness)

    await snapshotFails(harness)
    expect(unreachableBanner()).not.toBeInTheDocument()

    await nextSecond(harness)
    await snapshotSucceeds(harness)
    expect(unreachableBanner()).not.toBeInTheDocument()
  })

  it('two_failed_polls_show_gateway_unreachable', async () => {
    const harness = renderApp()

    await liveThenUnreachable(harness)

    expect(unreachableBanner()).toBeInTheDocument()
    expect(unreachableBanner()).toHaveClass('banner-error')
  })

  it('polling_continues_while_unreachable', async () => {
    const harness = renderApp()
    await liveThenUnreachable(harness)
    const requestsAtOutage = harness.snapshots.requestCount()

    await nextSecond(harness)
    await snapshotFails(harness)
    await nextSecond(harness)

    expect(harness.snapshots.requestCount()).toBe(requestsAtOutage + 2)
  })

  it('gateway_down_on_first_load_keeps_loading_with_banner', async () => {
    const harness = renderApp()

    await snapshotFails(harness)
    await nextSecond(harness)
    await snapshotFails(harness)

    expect(unreachableBanner()).toBeInTheDocument()
    expect(loadingIndicator()).toBeInTheDocument()
    expect(screen.getByRole('main')).not.toHaveTextContent(/\d/)
  })

  it('unreachable_greys_last_data_and_counts_seconds', async () => {
    const harness = renderApp()
    await liveThenUnreachable(harness)

    const regions = screen.getByTestId('data-regions')
    expect(regions).toHaveClass('is-stale')
    expect(screen.getByText(/last updated 2 s ago/)).toBeInTheDocument()

    await nextSecond(harness)
    await snapshotFails(harness)
    await nextSecond(harness)
    await snapshotFails(harness)
    await nextSecond(harness)

    expect(screen.getByText(/last updated 5 s ago/)).toBeInTheDocument()
    expect(region('Traffic')).toBeInTheDocument()
  })

  it('recovery_removes_banner_and_shows_reconnected_briefly', async () => {
    const harness = renderApp()
    await liveThenUnreachable(harness)
    await nextSecond(harness)

    await snapshotSucceeds(harness)

    expect(unreachableBanner()).not.toBeInTheDocument()
    expect(screen.queryByText(/last updated/)).not.toBeInTheDocument()
    expect(screen.getByTestId('data-regions')).not.toHaveClass('is-stale')
    expect(reconnectedNotice()).toBeInTheDocument()

    for (let second = 0; second < 4; second++) {
      await nextSecond(harness)
      await snapshotSucceeds(harness)
    }
    expect(reconnectedNotice()).not.toBeInTheDocument()
  })

  it('no_reconnected_notice_after_single_failure', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness)
    await nextSecond(harness)
    await snapshotFails(harness)
    await nextSecond(harness)

    await snapshotSucceeds(harness)

    expect(reconnectedNotice()).not.toBeInTheDocument()
  })

  it('redis_down_health_shows_degraded_banner', async () => {
    const harness = renderApp()

    await settle(() => harness.health.answerLatest(redisDown))

    expect(degradedBanner()).toBeInTheDocument()
    expect(degradedBanner()).toHaveClass('banner-warning')
  })

  it('redis_up_health_removes_degraded_banner', async () => {
    const harness = renderApp()
    await settle(() => harness.health.answerLatest(redisDown))

    await settle(() => harness.scheduler.advanceBy(5_000))
    await settle(() => harness.health.answerLatest(redisUp))

    expect(degradedBanner()).not.toBeInTheDocument()
  })

  it('health_failure_removes_degraded_banner', async () => {
    const harness = renderApp()
    await settle(() => harness.health.answerLatest(redisDown))

    await settle(() => harness.scheduler.advanceBy(5_000))
    await settle(() => harness.health.failLatest())

    expect(degradedBanner()).not.toBeInTheDocument()
  })

  it('null_metrics_show_metrics_unavailable_in_traffic_and_totals', async () => {
    const harness = renderApp()

    await snapshotSucceeds(harness, snapshotWithoutMetrics())

    expect(region('Traffic')).toHaveTextContent('Metrics unavailable')
    expect(region('Period totals')).toHaveTextContent('Metrics unavailable')
    expect(region('Tenants')).toBeInTheDocument()
    expect(region('Tenants')).not.toHaveTextContent('Metrics unavailable')
  })

  it('metrics_return_removes_marker', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness, snapshotWithoutMetrics())
    await nextSecond(harness)

    await snapshotSucceeds(harness)

    expect(screen.queryByText('Metrics unavailable')).not.toBeInTheDocument()
  })

  it('degraded_and_metrics_unavailable_show_together', async () => {
    const harness = renderApp()

    await settle(() => harness.health.answerLatest(redisDown))
    await snapshotSucceeds(harness, snapshotWithoutMetrics())

    expect(degradedBanner()).toBeInTheDocument()
    expect(screen.getAllByText('Metrics unavailable')).toHaveLength(2)
  })

  it('reconnected_while_redis_down_shows_notice_and_marker', async () => {
    const harness = renderApp()
    await liveThenUnreachable(harness)
    await nextSecond(harness)

    await snapshotSucceeds(harness, snapshotWithoutMetrics())

    expect(reconnectedNotice()).toBeInTheDocument()
    expect(screen.getAllByText('Metrics unavailable')).toHaveLength(2)
  })

  it('header_connecting_then_connected', async () => {
    const harness = renderApp()
    expect(header()).toHaveTextContent('Gateway Connecting')

    await snapshotSucceeds(harness)

    expect(header()).toHaveTextContent('Gateway Connected')
  })

  it('header_unreachable_after_two_failures_and_back', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness)
    await nextSecond(harness)
    await snapshotFails(harness)
    expect(header()).toHaveTextContent('Gateway Connected')

    await nextSecond(harness)
    await snapshotFails(harness)
    expect(header()).toHaveTextContent('Gateway Unreachable')

    await nextSecond(harness)
    await snapshotSucceeds(harness)
    expect(header()).toHaveTextContent('Gateway Connected')
  })

  it('header_enforcement_follows_health_polls', async () => {
    const harness = renderApp()
    expect(header()).toHaveTextContent('Enforcement Unknown')

    await settle(() => harness.health.answerLatest(redisUp))
    expect(header()).toHaveTextContent('Enforcement OK')

    await settle(() => harness.scheduler.advanceBy(5_000))
    await settle(() => harness.health.answerLatest(redisDown))
    expect(header()).toHaveTextContent('Enforcement Degraded')
    expect(degradedBanner()).toBeInTheDocument()

    await settle(() => harness.scheduler.advanceBy(5_000))
    await settle(() => harness.health.failLatest())
    expect(header()).toHaveTextContent('Enforcement Unknown')
  })

  it('countdown_ticks_between_polls_and_restarts_on_new_period', async () => {
    const harness = renderApp()
    expect(header()).toHaveTextContent('Resets in —')

    await snapshotSucceeds(harness, snapshotWithSecondsLeft(2))
    expect(header()).toHaveTextContent('Resets in 0:02')

    await nextSecond(harness)
    expect(header()).toHaveTextContent('Resets in 0:01')

    await snapshotSucceeds(harness, snapshotWithSecondsLeft(60, 100_001))
    expect(header()).toHaveTextContent('Resets in 1:00')
  })

  it('countdown_is_dash_while_unreachable_and_returns', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness, snapshotWithSecondsLeft(30))
    await nextSecond(harness)
    await snapshotFails(harness)
    await nextSecond(harness)
    await snapshotFails(harness)

    expect(header()).toHaveTextContent('Resets in —')

    await nextSecond(harness)
    await snapshotSucceeds(harness, snapshotWithSecondsLeft(12))
    expect(header()).toHaveTextContent('Resets in 0:12')
  })

  it('countdown_shown_while_metrics_unavailable', async () => {
    const harness = renderApp()

    await snapshotSucceeds(harness, snapshotWith({ metrics: null, period: { index: 1, endsAt: '1970-01-01T00:02:00Z', secondsLeft: 42 } }))

    expect(header()).toHaveTextContent('Resets in 0:42')
    expect(region('Traffic')).toHaveTextContent('Metrics unavailable')
  })

  it('traffic_and_totals_follow_polls', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness, snapshotWithTraffic(183, 12_045))

    expect(region('Traffic')).toHaveTextContent('Allowed 183 req/s')
    expect(region('Traffic')).toHaveTextContent('Rejected 4 req/s')
    expect(region('Period totals')).toHaveTextContent('Allowed 12,045')
    expect(region('Period totals')).toHaveTextContent('Near limit 2')
    expect(region('Period totals')).toHaveTextContent('Over limit 1')

    await nextSecond(harness)
    await snapshotSucceeds(harness, snapshotWithTraffic(190, 12_235))

    expect(region('Traffic')).toHaveTextContent('Allowed 190 req/s')
    expect(region('Period totals')).toHaveTextContent('Allowed 12,235')
    expect(screen.getByRole('img', { name: /peak 190 allowed and 4 rejected/ })).toBeInTheDocument()
  })

  it('panels_replaced_by_metrics_unavailable_and_return', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness, snapshotWithoutMetrics())

    expect(region('Traffic')).not.toHaveTextContent(/\d/)
    expect(region('Period totals')).not.toHaveTextContent(/\d/)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    await nextSecond(harness)
    await snapshotSucceeds(harness, snapshotWithTraffic(95, 300))

    expect(region('Traffic')).toHaveTextContent('Allowed 95 req/s')
    expect(region('Period totals')).toHaveTextContent('Allowed 300')
    expect(screen.getByRole('img', { name: /last 60 seconds/ })).toBeInTheDocument()
  })

  it('panels_keep_last_numbers_while_unreachable', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness, snapshotWithTraffic(183, 12_045))
    await nextSecond(harness)
    await snapshotFails(harness)
    await nextSecond(harness)
    await snapshotFails(harness)

    expect(screen.getByTestId('data-regions')).toHaveClass('is-stale')
    expect(region('Traffic')).toHaveTextContent('Allowed 183 req/s')
    expect(region('Period totals')).toHaveTextContent('Allowed 12,045')

    await nextSecond(harness)
    await snapshotSucceeds(harness, snapshotWithTraffic(95, 12_100))

    expect(screen.getByTestId('data-regions')).not.toHaveClass('is-stale')
    expect(region('Traffic')).toHaveTextContent('Allowed 95 req/s')
  })

  it('grid_shows_first_page_with_usage_and_count', async () => {
    const harness = renderApp()

    await liveWithTiers(harness)

    const table = within(region('Tenants')).getByRole('table')
    expect(within(table).getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual(seedNames)
    expect(rowOf('Blue Bank')).toHaveTextContent('10 / 100 (10%)')
    expect(within(rowOf('Blue Bank')).getByText('OK')).toBeInTheDocument()
    expect(region('Tenants')).toHaveTextContent('Showing 10 of 15 tenants')
  })

  it('tier_list_loads_after_failed_first_attempt', async () => {
    const harness = renderApp()
    await snapshotSucceeds(harness, firstPage())

    await settle(() => harness.tierAdministration.failTiers())
    await settle(() => harness.scheduler.advanceBy(5_000))
    expect(harness.tierAdministration.tierRequests).toHaveLength(2)
    await settle(() => harness.tierAdministration.answerTiers(defaultTiers))

    expect(within(selectorOf('Acme Corp')).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Bronze (100)',
      'Silver (1,000)',
      'Gold (10,000)',
    ])
    await settle(() => harness.scheduler.advanceBy(20_000))
    expect(harness.tierAdministration.tierRequests).toHaveLength(2)
  })

  it('selectors_disabled_until_tiers_load', async () => {
    const harness = renderApp()

    await snapshotSucceeds(harness, firstPage())

    expect(selectorOf('Acme Corp')).toBeDisabled()
    expect(within(selectorOf('Acme Corp')).getAllByRole('option')).toHaveLength(1)
  })

  it('tier_change_saves_then_shows_new_tier', async () => {
    const harness = renderApp()
    await liveWithTiers(harness, acmeAs('Bronze', 100, 85))

    await chooseTier('Acme Corp', 'Gold')

    expect(harness.tierAdministration.latestChange()).toMatchObject({ tenantId: 1, tier: 'Gold' })
    expect(selectorOf('Acme Corp')).toBeDisabled()
    expect(selectorOf('Acme Corp')).toHaveValue('Gold')
    expect(rowOf('Acme Corp')).toHaveTextContent('Saving…')
    expect(selectorOf('Blue Bank')).toBeEnabled()

    await settle(() => harness.tierAdministration.answerChange(changedTier()))

    expect(selectorOf('Acme Corp')).toBeEnabled()
    expect(selectorOf('Acme Corp')).toHaveValue('Gold')
    expect(rowOf('Acme Corp')).not.toHaveTextContent('Saving…')
    expect(rowOf('Acme Corp')).toHaveTextContent('85 / 10,000 (0%)')
  })

  it('older_snapshot_after_success_keeps_new_tier', async () => {
    const harness = renderApp()
    await liveWithTiers(harness)
    await nextSecond(harness)
    await chooseTier('Acme Corp', 'Gold')
    await settle(() => harness.tierAdministration.answerChange(changedTier()))

    await snapshotSucceeds(harness, acmeAs('Bronze', 100))
    expect(selectorOf('Acme Corp')).toHaveValue('Gold')

    await nextSecond(harness)
    await snapshotSucceeds(harness, acmeAs('Gold', 10000, 3))
    expect(selectorOf('Acme Corp')).toHaveValue('Gold')
    expect(rowOf('Acme Corp')).toHaveTextContent('3 / 10,000 (0%)')
  })

  it('tier_change_404_shows_error_and_reverts', async () => {
    const harness = renderApp()
    await liveWithTiers(harness)
    await chooseTier('Acme Corp', 'Gold')

    await settle(() => harness.tierAdministration.failChange(new TierChangeRefused(404)))

    expect(within(rowOf('Acme Corp')).getByRole('alert')).toHaveTextContent('Tier change failed: tenant not found')
    expect(selectorOf('Acme Corp')).toHaveValue('Bronze')
    expect(selectorOf('Acme Corp')).toBeEnabled()
  })

  it('tier_change_timeout_shows_error_and_reverts', async () => {
    const harness = renderApp()
    await liveWithTiers(harness)
    await chooseTier('Acme Corp', 'Gold')

    await settle(() => harness.scheduler.advanceBy(2_000))

    expect(harness.tierAdministration.latestChange().signal.aborted).toBe(true)
    expect(within(rowOf('Acme Corp')).getByRole('alert')).toHaveTextContent('Tier change failed: no answer within 2 s')
    expect(selectorOf('Acme Corp')).toHaveValue('Bronze')
  })

  it('retry_clears_error', async () => {
    const harness = renderApp()
    await liveWithTiers(harness)
    await chooseTier('Acme Corp', 'Gold')
    await settle(() => harness.tierAdministration.failChange(new TierChangeRefused(502)))
    expect(within(rowOf('Acme Corp')).getByRole('alert')).toHaveTextContent('Tier change failed: gateway error (502)')

    await chooseTier('Acme Corp', 'Silver')

    expect(within(rowOf('Acme Corp')).queryByRole('alert')).not.toBeInTheDocument()
    expect(harness.tierAdministration.changes).toHaveLength(2)
    expect(harness.tierAdministration.latestChange()).toMatchObject({ tenantId: 1, tier: 'Silver' })
  })

  it('selectors_disabled_while_unreachable_and_enabled_after_recovery', async () => {
    const harness = renderApp()
    await liveWithTiers(harness)
    await nextSecond(harness)
    await snapshotFails(harness)
    await nextSecond(harness)
    await snapshotFails(harness)

    expect(screen.getByTestId('data-regions')).toHaveClass('is-stale')
    for (const name of seedNames) {
      expect(selectorOf(name)).toBeDisabled()
    }

    await nextSecond(harness)
    await snapshotSucceeds(harness, firstPage())
    expect(selectorOf('Acme Corp')).toBeEnabled()
  })

  it('selectors_enabled_while_redis_down_and_metrics_unavailable', async () => {
    const harness = renderApp()
    await settle(() => harness.health.answerLatest(redisDown))
    await settle(() => harness.tierAdministration.answerTiers(defaultTiers))

    await snapshotSucceeds(harness, { ...firstPage(), metrics: null })

    expect(degradedBanner()).toBeInTheDocument()
    expect(selectorOf('Acme Corp')).toBeEnabled()
    await chooseTier('Acme Corp', 'Gold')
    expect(harness.tierAdministration.latestChange()).toMatchObject({ tenantId: 1, tier: 'Gold' })
  })

  it('rows_show_dashes_while_metrics_unavailable', async () => {
    const harness = renderApp()

    await liveWithTiers(harness, {
      ...firstPage(() => ({ used: null, rejected: null, status: null })),
      metrics: null,
    })

    const cells = within(rowOf('Acme Corp')).getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('1')
    expect(selectorOf('Acme Corp')).toHaveValue('Bronze')
    expect(cells.slice(2).map((cell) => cell.textContent)).toEqual(['—', '—', '—'])
  })
})
