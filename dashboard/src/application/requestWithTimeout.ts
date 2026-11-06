import type { Scheduler } from './ports.ts'

export class RequestTimedOut extends Error {
  constructor(timeoutMs: number) {
    super(`Request did not complete within ${timeoutMs} ms`)
    this.name = 'RequestTimedOut'
  }
}

export function requestWithTimeout<T>(
  task: (signal: AbortSignal) => Promise<T>,
  timeoutMs: number,
  scheduler: Scheduler,
  outerSignal?: AbortSignal,
): Promise<T> {
  const controller = new AbortController()
  const abortTask = () => controller.abort()

  return new Promise<T>((resolve, reject) => {
    let settled = false
    const settle = (report: () => void) => {
      if (settled) {
        return
      }
      settled = true
      cancelTimeout()
      outerSignal?.removeEventListener('abort', abortTask)
      report()
    }

    const cancelTimeout = scheduler.schedule(() => {
      controller.abort()
      settle(() => reject(new RequestTimedOut(timeoutMs)))
    }, timeoutMs)

    if (outerSignal?.aborted) {
      controller.abort()
    } else {
      outerSignal?.addEventListener('abort', abortTask, { once: true })
    }

    started(task, controller.signal).then(
      (result) => settle(() => resolve(result)),
      (error: unknown) => settle(() => reject(error)),
    )
  })
}

function started<T>(task: (signal: AbortSignal) => Promise<T>, signal: AbortSignal): Promise<T> {
  try {
    return task(signal)
  } catch (error) {
    return Promise.reject(error)
  }
}
