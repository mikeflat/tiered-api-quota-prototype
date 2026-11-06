import type { Scheduler } from '../../application/ports.ts'

export const browserScheduler: Scheduler = {
  now: () => Date.now(),
  schedule(callback, delayMs) {
    const handle = globalThis.setTimeout(callback, delayMs)
    return () => globalThis.clearTimeout(handle)
  },
}
