import { describe, expect, it } from 'vitest'
import { RequestTimedOut, requestWithTimeout } from '../../src/application/requestWithTimeout.ts'
import { deferred, flushPromises } from '../support/deferred.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'

function pendingTask() {
  const reply = deferred<string>()
  const signals: AbortSignal[] = []
  const task = (signal: AbortSignal) => {
    signals.push(signal)
    return reply.promise
  }
  return { reply, signals, task }
}

describe('requestWithTimeout', () => {
  it('resolves_with_task_result', async () => {
    const scheduler = new ManualScheduler()
    const { reply, task } = pendingTask()

    const result = requestWithTimeout(task, 2_000, scheduler)
    reply.resolve('done')

    await expect(result).resolves.toBe('done')
  })

  it('rejects_and_aborts_after_timeout', async () => {
    const scheduler = new ManualScheduler()
    const { signals, task } = pendingTask()

    const result = requestWithTimeout(task, 2_000, scheduler)
    scheduler.advanceBy(1_999)
    expect(signals[0]?.aborted).toBe(false)
    scheduler.advanceBy(1)

    await expect(result).rejects.toBeInstanceOf(RequestTimedOut)
    expect(signals[0]?.aborted).toBe(true)
  })

  it('outer_abort_aborts_task', () => {
    const scheduler = new ManualScheduler()
    const { signals, task } = pendingTask()
    const outer = new AbortController()

    void requestWithTimeout(task, 2_000, scheduler, outer.signal).catch(() => {})
    outer.abort()

    expect(signals[0]?.aborted).toBe(true)
  })

  it('timer_is_cancelled_after_settle', async () => {
    const scheduler = new ManualScheduler()
    const { reply, task } = pendingTask()

    const result = requestWithTimeout(task, 2_000, scheduler)
    reply.reject(new TypeError('Failed to fetch'))
    await flushPromises()

    expect(scheduler.pendingCount()).toBe(0)
    await expect(result).rejects.toBeInstanceOf(TypeError)
  })
})
