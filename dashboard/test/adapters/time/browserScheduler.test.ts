import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { browserScheduler } from '../../../src/adapters/time/browserScheduler.ts'

describe('browserScheduler', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('scheduled_callback_runs_after_delay', () => {
    const callback = vi.fn()
    browserScheduler.schedule(callback, 1_000)

    vi.advanceTimersByTime(999)
    expect(callback).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(callback).toHaveBeenCalledOnce()
  })

  it('cancel_prevents_callback', () => {
    const callback = vi.fn()
    const cancel = browserScheduler.schedule(callback, 1_000)

    cancel()
    vi.advanceTimersByTime(5_000)

    expect(callback).not.toHaveBeenCalled()
  })

  it('now_reads_system_time', () => {
    vi.setSystemTime(new Date('2026-11-06T10:00:00Z'))

    expect(browserScheduler.now()).toBe(Date.parse('2026-11-06T10:00:00Z'))
  })
})
