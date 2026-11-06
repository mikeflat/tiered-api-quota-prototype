import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { DashboardPortsProvider } from '../../src/application/DashboardPorts.tsx'
import { useDashboardScreen } from '../../src/application/useDashboardScreen.ts'
import type { TenantPage } from '../../src/domain/snapshot.ts'
import { flushPromises } from '../support/deferred.ts'
import { FakeHealthSource, FakeSnapshotSource } from '../support/fakeSources.ts'
import { FakeTierAdministration } from '../support/fakeTierAdministration.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'
import { snapshotWith } from '../support/snapshots.ts'
import { defaultTiers } from '../support/tiers.ts'

function renderScreen() {
  const scheduler = new ManualScheduler(100_000)
  const snapshots = new FakeSnapshotSource()
  const tierAdministration = new FakeTierAdministration()
  const ports = { snapshots, health: new FakeHealthSource(), scheduler, tierAdministration }
  const wrapper = ({ children }: { children: ReactNode }) => (
    <DashboardPortsProvider ports={ports}>{children}</DashboardPortsProvider>
  )
  const hook = renderHook(() => useDashboardScreen(), { wrapper })
  return { scheduler, snapshots, tierAdministration, hook }
}

async function settle(action: () => void): Promise<void> {
  await act(async () => {
    action()
    await flushPromises()
  })
}

function snapshotOfPage(tenants: Partial<TenantPage>) {
  const base = snapshotWith()
  return snapshotWith({ tenants: { ...base.tenants, ...tenants } })
}

describe('useDashboardScreen', () => {
  it('filters_flow_into_snapshot_requests', async () => {
    const { hook, snapshots } = renderScreen()
    await settle(() => snapshots.answerLatest(snapshotWith()))

    await settle(() => hook.result.current.tenantActions.setTier('Gold'))

    expect(snapshots.latestQuery().filters.tier).toBe('Gold')
    expect(hook.result.current.filters.tier).toBe('Gold')
  })

  it('grid_is_updating_until_answer_for_new_query', async () => {
    const { hook, snapshots } = renderScreen()
    await settle(() => snapshots.answerLatest(snapshotOfPage({ total: 15 })))

    await settle(() => hook.result.current.tenantActions.nextPage())
    expect(hook.result.current.grid).toMatchObject({ kind: 'rows', updating: true })

    await settle(() => snapshots.answerLatest(snapshotOfPage({ total: 15, page: 1 })))
    expect(hook.result.current.grid).toMatchObject({ kind: 'rows', updating: false, pageNumber: 2 })
  })

  it('page_past_end_is_corrected_once', async () => {
    const { hook, snapshots } = renderScreen()
    await settle(() => snapshots.answerLatest(snapshotOfPage({ total: 15 })))
    await settle(() => hook.result.current.tenantActions.nextPage())
    const requestsBefore = snapshots.requestCount()

    await settle(() => snapshots.answerLatest(snapshotOfPage({ rows: [], total: 4, page: 1 })))

    expect(snapshots.requestCount()).toBe(requestsBefore + 1)
    expect(snapshots.latestQuery().page).toBe(0)

    await settle(() => snapshots.answerLatest(snapshotOfPage({ total: 4, page: 0 })))
    expect(snapshots.requestCount()).toBe(requestsBefore + 1)
    expect(hook.result.current.grid).toMatchObject({ pageNumber: 1, pageCount: 1, updating: false })
  })

  it('status_control_disabled_while_metrics_unavailable', async () => {
    const { hook, snapshots, tierAdministration } = renderScreen()
    await settle(() => tierAdministration.answerTiers(defaultTiers))

    await settle(() => snapshots.answerLatest(snapshotWith({ metrics: null })))

    expect(hook.result.current.filters).toMatchObject({ enabled: true, statusEnabled: false })
    expect(hook.result.current.filters.tierOptions).toEqual(['Bronze', 'Silver', 'Gold'])
  })
})
