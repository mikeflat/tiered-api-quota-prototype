import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { DashboardPortsProvider } from '../../src/application/DashboardPorts.tsx'
import { useTiers } from '../../src/application/useTiers.ts'
import { flushPromises } from '../support/deferred.ts'
import { FakeHealthSource, FakeSnapshotSource } from '../support/fakeSources.ts'
import { FakeTierAdministration } from '../support/fakeTierAdministration.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'
import { defaultTiers } from '../support/tiers.ts'

function renderTiers() {
  const scheduler = new ManualScheduler(100_000)
  const tierAdministration = new FakeTierAdministration()
  const ports = { snapshots: new FakeSnapshotSource(), health: new FakeHealthSource(), scheduler, tierAdministration }
  const wrapper = ({ children }: { children: ReactNode }) => (
    <DashboardPortsProvider ports={ports}>{children}</DashboardPortsProvider>
  )
  const hook = renderHook(() => useTiers(), { wrapper })
  return { scheduler, tierAdministration, hook }
}

async function settle(action: () => void): Promise<void> {
  await act(async () => {
    action()
    await flushPromises()
  })
}

describe('useTiers', () => {
  it('requests_tiers_on_start', () => {
    const { hook, tierAdministration } = renderTiers()

    expect(tierAdministration.tierRequests).toHaveLength(1)
    expect(hook.result.current).toBeNull()
  })

  it('returns_tiers_after_success_and_stops', async () => {
    const { hook, scheduler, tierAdministration } = renderTiers()

    await settle(() => tierAdministration.answerTiers(defaultTiers))
    await settle(() => scheduler.advanceBy(20_000))

    expect(hook.result.current).toEqual(defaultTiers)
    expect(tierAdministration.tierRequests).toHaveLength(1)
  })

  it('retries_every_five_seconds_after_failure', async () => {
    const { hook, scheduler, tierAdministration } = renderTiers()

    await settle(() => tierAdministration.failTiers())
    await settle(() => scheduler.advanceBy(4_999))
    expect(tierAdministration.tierRequests).toHaveLength(1)

    await settle(() => scheduler.advanceBy(1))
    expect(tierAdministration.tierRequests).toHaveLength(2)

    await settle(() => tierAdministration.answerTiers(defaultTiers))
    expect(hook.result.current).toEqual(defaultTiers)
  })

  it('times_out_after_two_seconds_and_retries', async () => {
    const { scheduler, tierAdministration } = renderTiers()

    await settle(() => scheduler.advanceBy(2_000))
    expect(tierAdministration.tierRequests[0]?.signal.aborted).toBe(true)

    await settle(() => scheduler.advanceBy(3_000))
    expect(tierAdministration.tierRequests).toHaveLength(2)
  })

  it('unmount_stops_retrying', async () => {
    const { hook, scheduler, tierAdministration } = renderTiers()
    await settle(() => tierAdministration.failTiers())

    hook.unmount()
    await settle(() => scheduler.advanceBy(20_000))

    expect(tierAdministration.tierRequests).toHaveLength(1)
  })
})
