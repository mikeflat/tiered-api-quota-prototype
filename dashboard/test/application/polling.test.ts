import { describe, expect, it } from 'vitest'
import { type Deferred, deferred, flushPromises } from '../support/deferred.ts'
import { ManualScheduler } from '../support/manualScheduler.ts'
import { PollTimedOut, startPolling } from '../../src/application/polling.ts'

interface Harness {
  readonly scheduler: ManualScheduler
  readonly calls: { readonly startedAt: number; readonly signal: AbortSignal; readonly reply: Deferred<string> }[]
  readonly successes: string[]
  readonly failures: unknown[]
  readonly stop: () => void
}

function startHarness(): Harness {
  const scheduler = new ManualScheduler(1_000)
  const calls: Harness['calls'][number][] = []
  const successes: string[] = []
  const failures: unknown[] = []
  const stop = startPolling<string>({
    task: (signal) => {
      const reply = deferred<string>()
      calls.push({ startedAt: scheduler.now(), signal, reply })
      return reply.promise
    },
    intervalMs: 1_000,
    timeoutMs: 2_000,
    scheduler,
    onSuccess: (result) => successes.push(result),
    onFailure: (error) => failures.push(error),
  })
  return { scheduler, calls, successes, failures, stop }
}

async function answer(harness: Harness, index: number, value: string): Promise<void> {
  harness.calls[index].reply.resolve(value)
  await flushPromises()
}

async function failCall(harness: Harness, index: number): Promise<void> {
  harness.calls[index].reply.reject(new TypeError('network error'))
  await flushPromises()
}

describe('startPolling', () => {
  it('first_poll_starts_immediately', () => {
    const harness = startHarness()

    expect(harness.calls).toHaveLength(1)
    expect(harness.calls[0].startedAt).toBe(1_000)
  })

  it('next_poll_starts_one_interval_after_previous_start', async () => {
    const harness = startHarness()
    harness.scheduler.advanceBy(200)
    await answer(harness, 0, 'first')

    harness.scheduler.advanceBy(799)
    expect(harness.calls).toHaveLength(1)

    harness.scheduler.advanceBy(1)
    expect(harness.calls).toHaveLength(2)
    expect(harness.calls[1].startedAt).toBe(2_000)
    expect(harness.successes).toEqual(['first'])
  })

  it('slow_poll_is_never_overlapped', () => {
    const harness = startHarness()

    harness.scheduler.advanceBy(1_500)

    expect(harness.calls).toHaveLength(1)
  })

  it('slow_poll_is_followed_immediately', async () => {
    const harness = startHarness()
    harness.scheduler.advanceBy(1_500)
    await answer(harness, 0, 'slow')

    harness.scheduler.advanceBy(0)

    expect(harness.calls).toHaveLength(2)
    expect(harness.calls[1].startedAt).toBe(2_500)
  })

  it('poll_exceeding_timeout_is_aborted_and_reported_failed_once', async () => {
    const harness = startHarness()

    harness.scheduler.advanceBy(2_000)
    expect(harness.calls[0].signal.aborted).toBe(true)
    expect(harness.failures).toHaveLength(1)
    expect(harness.failures[0]).toBeInstanceOf(PollTimedOut)
    expect(harness.calls).toHaveLength(2)

    await failCall(harness, 0)
    await answer(harness, 1, 'late')
    expect(harness.failures).toHaveLength(1)
    expect(harness.successes).toEqual(['late'])
  })

  it('polling_continues_after_failures', async () => {
    const harness = startHarness()

    for (let poll = 0; poll < 3; poll++) {
      await failCall(harness, poll)
      harness.scheduler.advanceBy(1_000)
    }

    expect(harness.failures).toHaveLength(3)
    expect(harness.calls).toHaveLength(4)
  })

  it('stop_aborts_in_flight_request_and_schedules_nothing', () => {
    const harness = startHarness()

    harness.stop()
    harness.scheduler.advanceBy(10_000)

    expect(harness.calls[0].signal.aborted).toBe(true)
    expect(harness.calls).toHaveLength(1)
    expect(harness.scheduler.pendingCount()).toBe(0)
  })

  it('outcome_after_stop_is_ignored', async () => {
    const harness = startHarness()

    harness.stop()
    await answer(harness, 0, 'too late')

    expect(harness.successes).toEqual([])
    expect(harness.failures).toEqual([])
    expect(harness.scheduler.pendingCount()).toBe(0)
  })

  it('task_throwing_synchronously_counts_as_failure', async () => {
    const scheduler = new ManualScheduler()
    const failures: unknown[] = []
    startPolling({
      task: () => {
        throw new Error('boom')
      },
      intervalMs: 1_000,
      timeoutMs: 2_000,
      scheduler,
      onSuccess: () => {},
      onFailure: (error) => failures.push(error),
    })
    await flushPromises()

    expect(failures).toHaveLength(1)
  })
})
