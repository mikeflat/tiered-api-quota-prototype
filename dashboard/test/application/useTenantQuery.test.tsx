import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { DashboardPortsProvider } from '../../src/application/DashboardPorts.tsx'
import { useTenantQuery } from '../../src/application/useTenantQuery.ts'
import { initialTenantQuery, noFilters } from '../../src/domain/tenantQuery.ts'
import { FakeHealthSource, FakeSnapshotSource } from '../support/fakeSources.ts'
import { FakeTierAdministration } from '../support/fakeTierAdministration.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'

function renderQuery() {
  const scheduler = new ManualScheduler(100_000)
  const ports = {
    snapshots: new FakeSnapshotSource(),
    health: new FakeHealthSource(),
    scheduler,
    tierAdministration: new FakeTierAdministration(),
  }
  const wrapper = ({ children }: { children: ReactNode }) => (
    <DashboardPortsProvider ports={ports}>{children}</DashboardPortsProvider>
  )
  const hook = renderHook(() => useTenantQuery(), { wrapper })
  const actions = () => hook.result.current.actions
  const filters = () => hook.result.current.query.filters
  const page = () => hook.result.current.query.page
  return { scheduler, hook, actions, filters, page }
}

describe('useTenantQuery', () => {
  it('typing_name_applies_after_three_hundred_ms', () => {
    const { scheduler, hook, actions, filters } = renderQuery()

    act(() => actions().setNameText(' bank '))
    expect(hook.result.current.drafts.nameText).toBe(' bank ')
    act(() => scheduler.advanceBy(299))
    expect(filters().name).toBe('')

    act(() => scheduler.advanceBy(1))
    expect(filters().name).toBe('bank')
  })

  it('each_keystroke_restarts_the_pause', () => {
    const { scheduler, actions, filters } = renderQuery()

    act(() => actions().setNameText('b'))
    act(() => scheduler.advanceBy(200))
    act(() => actions().setNameText('ba'))
    act(() => scheduler.advanceBy(200))
    expect(filters().name).toBe('')

    act(() => scheduler.advanceBy(100))
    expect(filters().name).toBe('ba')
  })

  it('valid_id_is_applied', () => {
    const { scheduler, actions, filters } = renderQuery()

    act(() => actions().setIdText('3'))
    act(() => scheduler.advanceBy(300))

    expect(filters().id).toBe(3)
  })

  it('invalid_id_keeps_applied_query', () => {
    const { scheduler, hook, actions } = renderQuery()
    act(() => actions().setIdText('3'))
    act(() => scheduler.advanceBy(300))
    const applied = hook.result.current.query

    act(() => actions().setIdText('3x'))
    act(() => scheduler.advanceBy(300))

    expect(hook.result.current.query).toBe(applied)
    expect(hook.result.current.drafts.idText).toBe('3x')
  })

  it('tier_and_status_apply_at_once_on_first_page', () => {
    const { actions, filters, page } = renderQuery()
    act(() => actions().nextPage())
    expect(page()).toBe(1)

    act(() => actions().setTier('Silver'))
    expect(filters().tier).toBe('Silver')
    expect(page()).toBe(0)

    act(() => actions().nextPage())
    act(() => actions().setStatus('over'))
    expect(filters()).toEqual({ ...noFilters, tier: 'Silver', status: 'over' })
    expect(page()).toBe(0)
  })

  it('choice_folds_in_pending_text', () => {
    const { scheduler, actions, filters } = renderQuery()

    act(() => actions().setNameText('co'))
    act(() => actions().setTier('Bronze'))
    expect(filters()).toEqual({ ...noFilters, name: 'co', tier: 'Bronze' })

    act(() => scheduler.advanceBy(1_000))
    expect(filters()).toEqual({ ...noFilters, name: 'co', tier: 'Bronze' })
  })

  it('clear_filters_resets_drafts_and_query', () => {
    const { scheduler, hook, actions } = renderQuery()
    act(() => actions().setNameText('bank'))
    act(() => actions().setStatus('near'))
    act(() => actions().setIdText('4'))

    act(() => actions().clearFilters())
    act(() => scheduler.advanceBy(1_000))

    expect(hook.result.current.query).toEqual(initialTenantQuery)
    expect(hook.result.current.drafts).toEqual({ nameText: '', idText: '' })
  })

  it('clear_status_keeps_other_filters', () => {
    const { actions, filters } = renderQuery()
    act(() => actions().setTier('Gold'))
    act(() => actions().setStatus('over'))

    act(() => actions().clearStatus())

    expect(filters()).toEqual({ ...noFilters, tier: 'Gold' })
  })

  it('next_and_previous_change_page', () => {
    const { actions, page } = renderQuery()

    act(() => actions().nextPage())
    act(() => actions().nextPage())
    expect(page()).toBe(2)
    act(() => actions().previousPage())
    expect(page()).toBe(1)
    act(() => actions().goToPage(0))
    act(() => actions().previousPage())
    expect(page()).toBe(0)
  })

  it('unmount_cancels_pending_apply', () => {
    const { scheduler, hook, actions } = renderQuery()
    act(() => actions().setNameText('bank'))

    hook.unmount()

    expect(scheduler.pendingCount()).toBe(0)
  })
})
