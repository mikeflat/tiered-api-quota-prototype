import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ManualScheduler } from '../support/manualScheduler.ts'
import type { Scheduler } from '../../src/application/ports.ts'
import { usePolling } from '../../src/application/usePolling.ts'

function renderPolling(scheduler: Scheduler, signals: AbortSignal[]) {
  return renderHook(() =>
    usePolling({
      task: (signal) => {
        signals.push(signal)
        return new Promise<never>(() => {})
      },
      intervalMs: 1_000,
      timeoutMs: 2_000,
      scheduler,
      onSuccess: () => {},
      onFailure: () => {},
    }),
  )
}

describe('usePolling', () => {
  it('unmount_stops_polling', () => {
    const scheduler = new ManualScheduler()
    const signals: AbortSignal[] = []
    const { unmount } = renderPolling(scheduler, signals)

    unmount()
    scheduler.advanceBy(10_000)

    expect(signals).toHaveLength(1)
    expect(signals[0].aborted).toBe(true)
    expect(scheduler.pendingCount()).toBe(0)
  })

  it('remount_starts_a_fresh_loop', () => {
    const scheduler = new ManualScheduler()
    const signals: AbortSignal[] = []
    renderPolling(scheduler, signals).unmount()

    renderPolling(scheduler, signals)

    expect(signals).toHaveLength(2)
    expect(signals[1].aborted).toBe(false)
  })
})
