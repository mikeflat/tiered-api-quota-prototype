import { nextPollDelay } from '../domain/pollDelay.ts'
import type { Cancel, Scheduler } from './ports.ts'

export interface PollingOptions<T> {
  readonly task: (signal: AbortSignal) => Promise<T>
  readonly intervalMs: number
  readonly timeoutMs: number
  readonly scheduler: Scheduler
  readonly onSuccess: (result: T) => void
  readonly onFailure: (error: unknown) => void
}

export type Stop = () => void

export class PollTimedOut extends Error {
  constructor(timeoutMs: number) {
    super(`Poll did not complete within ${timeoutMs} ms`)
    this.name = 'PollTimedOut'
  }
}

const nothingToCancel: Cancel = () => {}

export function startPolling<T>(options: PollingOptions<T>): Stop {
  const { task, intervalMs, timeoutMs, scheduler, onSuccess, onFailure } = options
  let stopped = false
  let inFlight: AbortController | null = null
  let cancelTimeout = nothingToCancel
  let cancelNextPoll = nothingToCancel

  const poll = () => {
    const startedAt = scheduler.now()
    const controller = new AbortController()
    inFlight = controller
    let settled = false

    const settle = (report: () => void) => {
      if (settled || stopped) {
        return
      }
      settled = true
      inFlight = null
      cancelTimeout()
      report()
      if (!stopped) {
        cancelNextPoll = scheduler.schedule(poll, nextPollDelay(intervalMs, startedAt, scheduler.now()))
      }
    }

    cancelTimeout = scheduler.schedule(() => {
      controller.abort()
      settle(() => onFailure(new PollTimedOut(timeoutMs)))
    }, timeoutMs)

    invoke(task, controller.signal).then(
      (result) => settle(() => onSuccess(result)),
      (error: unknown) => settle(() => onFailure(error)),
    )
  }

  poll()

  return () => {
    stopped = true
    inFlight?.abort()
    inFlight = null
    cancelTimeout()
    cancelNextPoll()
  }
}

function invoke<T>(task: (signal: AbortSignal) => Promise<T>, signal: AbortSignal): Promise<T> {
  try {
    return task(signal)
  } catch (error) {
    return Promise.reject(error)
  }
}
