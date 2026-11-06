import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { flushPromises } from '../support/deferred.ts'
import { FakeHealthSource, FakeSnapshotSource, redisDown, redisUp } from '../support/fakeSources.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'
import { snapshotWith } from '../support/snapshots.ts'
import { DashboardPortsProvider } from '../../src/application/DashboardPorts.tsx'
import { useDashboard } from '../../src/application/useDashboard.ts'

function renderDashboard() {
  const scheduler = new ManualScheduler(100_000)
  const snapshots = new FakeSnapshotSource()
  const health = new FakeHealthSource()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <DashboardPortsProvider ports={{ snapshots, health, scheduler }}>{children}</DashboardPortsProvider>
  )
  const hook = renderHook(() => useDashboard(), { wrapper })
  return { scheduler, snapshots, health, hook }
}

async function settle(action: () => void): Promise<void> {
  await act(async () => {
    action()
    await flushPromises()
  })
}

describe('useDashboard', () => {
  it('starts_loading', () => {
    const { hook, snapshots, health } = renderDashboard()

    expect(hook.result.current.loading).toBe(true)
    expect(snapshots.requestCount()).toBe(1)
    expect(health.requestCount()).toBe(1)
  })

  it('snapshot_success_makes_view_live', async () => {
    const { hook, snapshots } = renderDashboard()
    const snapshot = snapshotWith()

    await settle(() => snapshots.answerLatest(snapshot))

    expect(hook.result.current).toMatchObject({ loading: false, snapshot, tierControlsEnabled: true })
  })

  it('two_snapshot_failures_make_view_unreachable', async () => {
    const { hook, snapshots, scheduler } = renderDashboard()
    await settle(() => snapshots.answerLatest(snapshotWith()))
    await settle(() => scheduler.advanceBy(1_000))
    await settle(() => snapshots.failLatest())
    expect(hook.result.current.gatewayUnreachable).toBe(false)

    await settle(() => scheduler.advanceBy(1_000))
    await settle(() => snapshots.failLatest())

    expect(hook.result.current).toMatchObject({ gatewayUnreachable: true, stale: true, tierControlsEnabled: false })
  })

  it('health_redis_down_marks_enforcement_degraded', async () => {
    const { hook, health } = renderDashboard()

    await settle(() => health.answerLatest(redisDown))

    expect(hook.result.current.enforcementDegraded).toBe(true)
  })

  it('health_failure_clears_degraded', async () => {
    const { hook, health, scheduler } = renderDashboard()
    await settle(() => health.answerLatest(redisDown))

    await settle(() => scheduler.advanceBy(5_000))
    await settle(() => health.failLatest())

    expect(hook.result.current.enforcementDegraded).toBe(false)
  })

  it('health_redis_up_clears_degraded', async () => {
    const { hook, health, scheduler } = renderDashboard()
    await settle(() => health.answerLatest(redisDown))

    await settle(() => scheduler.advanceBy(5_000))
    await settle(() => health.answerLatest(redisUp))

    expect(hook.result.current.enforcementDegraded).toBe(false)
  })

  it('health_failures_never_make_gateway_unreachable', async () => {
    const { hook, health, snapshots, scheduler } = renderDashboard()
    await settle(() => snapshots.answerLatest(snapshotWith()))

    for (let poll = 0; poll < 3; poll++) {
      await settle(() => health.failLatest())
      for (let second = 0; second < 5; second++) {
        await settle(() => scheduler.advanceBy(1_000))
        await settle(() => snapshots.answerLatest(snapshotWith()))
      }
    }

    expect(health.requestCount()).toBe(4)
    expect(hook.result.current.gatewayUnreachable).toBe(false)
  })

  it('last_updated_seconds_advance_with_tick', async () => {
    const { hook, snapshots, scheduler } = renderDashboard()
    await settle(() => snapshots.answerLatest(snapshotWith()))
    await settle(() => scheduler.advanceBy(1_000))
    await settle(() => snapshots.failLatest())
    await settle(() => scheduler.advanceBy(1_000))
    await settle(() => snapshots.failLatest())
    const secondsAtOutage = hook.result.current.lastUpdatedSecondsAgo

    await settle(() => scheduler.advanceBy(3_000))

    expect(secondsAtOutage).toBe(2)
    expect(hook.result.current.lastUpdatedSecondsAgo).toBe(5)
  })
})
