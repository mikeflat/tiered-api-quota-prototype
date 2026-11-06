import { act, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DashboardPortsProvider } from '../../src/application/DashboardPorts.tsx'
import { flushPromises } from '../support/deferred.ts'
import { FakeHealthSource, FakeSnapshotSource, redisDown, redisUp } from '../support/fakeSources.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'
import { snapshotWith, snapshotWithoutMetrics } from '../support/snapshots.ts'
import App from '../../src/ui/App.tsx'

function renderApp() {
  const scheduler = new ManualScheduler(1_700_000_000_000)
  const snapshots = new FakeSnapshotSource()
  const health = new FakeHealthSource()
  render(
    <DashboardPortsProvider ports={{ snapshots, health, scheduler }}>
      <App />
    </DashboardPortsProvider>,
  )
  return { scheduler, snapshots, health }
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
})
