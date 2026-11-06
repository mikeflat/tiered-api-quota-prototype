import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { DashboardPortsProvider } from '../../src/application/DashboardPorts.tsx'
import { useTenantGrid } from '../../src/application/useTenantGrid.ts'
import type { DashboardView } from '../../src/domain/dashboardView.ts'
import type { GridRow, TenantGridView } from '../../src/domain/tenantGrid.ts'
import { TierChangeRefused } from '../../src/domain/tierChange.ts'
import type { AdminSnapshot } from '../../src/domain/snapshot.ts'
import { flushPromises } from '../support/deferred.ts'
import { FakeHealthSource, FakeSnapshotSource } from '../support/fakeSources.ts'
import { FakeTierAdministration } from '../support/fakeTierAdministration.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'
import { snapshotWith } from '../support/snapshots.ts'
import { changedTier, defaultTiers, tenantRow } from '../support/tiers.ts'

function liveViewOf(snapshot: AdminSnapshot): DashboardView {
  return {
    loading: false,
    gatewayUnreachable: false,
    snapshot,
    stale: false,
    lastUpdatedSecondsAgo: null,
    reconnectedNoticeVisible: false,
    enforcementDegraded: false,
    metricsAvailable: true,
    tierControlsEnabled: true,
    connectionStatus: 'connected',
    enforcement: 'ok',
    secondsUntilReset: 15,
  }
}

function snapshotWithRow(overrides: Parameters<typeof tenantRow>[0] = {}): AdminSnapshot {
  const base = snapshotWith()
  return snapshotWith({ tenants: { ...base.tenants, rows: [tenantRow(overrides)] } })
}

async function settle(action: () => void): Promise<void> {
  await act(async () => {
    action()
    await flushPromises()
  })
}

async function renderGrid(initial: AdminSnapshot = snapshotWithRow()) {
  const scheduler = new ManualScheduler(100_000)
  const tierAdministration = new FakeTierAdministration()
  const ports = { snapshots: new FakeSnapshotSource(), health: new FakeHealthSource(), scheduler, tierAdministration }
  const wrapper = ({ children }: { children: ReactNode }) => (
    <DashboardPortsProvider ports={ports}>{children}</DashboardPortsProvider>
  )
  const hook = renderHook(({ view }: { view: DashboardView }) => useTenantGrid(view), {
    wrapper,
    initialProps: { view: liveViewOf(initial) },
  })
  await settle(() => tierAdministration.answerTiers(defaultTiers))
  return { scheduler, tierAdministration, hook }
}

function firstRow(grid: TenantGridView | null): GridRow {
  if (grid?.kind !== 'rows' || grid.rows[0] === undefined) {
    throw new Error('grid has no rows')
  }
  return grid.rows[0]
}

describe('useTenantGrid', () => {
  it('grid_follows_shown_snapshot', async () => {
    const { hook } = await renderGrid()
    expect(firstRow(hook.result.current.grid)).toMatchObject({ used: 85, selectorEnabled: true })

    hook.rerender({ view: liveViewOf(snapshotWithRow({ used: 86 })) })

    expect(firstRow(hook.result.current.grid).used).toBe(86)
  })

  it('choosing_other_tier_sends_change', async () => {
    const { hook, tierAdministration } = await renderGrid()

    await settle(() => hook.result.current.changeTier(1, 'Gold'))

    expect(tierAdministration.changes.map(({ tenantId, tier }) => ({ tenantId, tier }))).toEqual([
      { tenantId: 1, tier: 'Gold' },
    ])
    expect(firstRow(hook.result.current.grid)).toMatchObject({ tier: 'Gold', saving: true, selectorEnabled: false })
  })

  it('choosing_same_tier_sends_nothing', async () => {
    const { hook, tierAdministration } = await renderGrid()

    await settle(() => hook.result.current.changeTier(1, 'Bronze'))

    expect(tierAdministration.changes).toHaveLength(0)
  })

  it('success_keeps_new_tier_until_snapshot_reports_it', async () => {
    const { hook, tierAdministration } = await renderGrid()
    await settle(() => hook.result.current.changeTier(1, 'Gold'))

    await settle(() => tierAdministration.answerChange(changedTier()))
    expect(firstRow(hook.result.current.grid)).toMatchObject({ tier: 'Gold', limit: 10000, saving: false })

    hook.rerender({ view: liveViewOf(snapshotWithRow({ used: 87 })) })
    expect(firstRow(hook.result.current.grid)).toMatchObject({ tier: 'Gold', limit: 10000 })

    await settle(() => hook.rerender({ view: liveViewOf(snapshotWithRow({ tier: 'Gold', limit: 10000, used: 88 })) }))
    hook.rerender({ view: liveViewOf(snapshotWithRow({ tier: 'Bronze', limit: 100, used: 89 })) })
    expect(firstRow(hook.result.current.grid)).toMatchObject({ tier: 'Bronze', limit: 100 })
  })

  it('error_status_reverts_with_failure', async () => {
    const { hook, tierAdministration } = await renderGrid()
    await settle(() => hook.result.current.changeTier(1, 'Gold'))

    await settle(() => tierAdministration.failChange(new TierChangeRefused(404)))

    expect(firstRow(hook.result.current.grid)).toMatchObject({
      tier: 'Bronze',
      saving: false,
      failure: { kind: 'notFound' },
      selectorEnabled: true,
    })
  })

  it('no_answer_within_two_seconds_reverts_with_timeout', async () => {
    const { hook, scheduler, tierAdministration } = await renderGrid()
    await settle(() => hook.result.current.changeTier(1, 'Gold'))

    await settle(() => scheduler.advanceBy(2_000))

    expect(tierAdministration.latestChange().signal.aborted).toBe(true)
    expect(firstRow(hook.result.current.grid)).toMatchObject({ tier: 'Bronze', failure: { kind: 'timedOut' } })
  })

  it('unmount_aborts_change_in_flight', async () => {
    const { hook, tierAdministration } = await renderGrid()
    await settle(() => hook.result.current.changeTier(1, 'Gold'))

    hook.unmount()

    expect(tierAdministration.latestChange().signal.aborted).toBe(true)
  })
})
