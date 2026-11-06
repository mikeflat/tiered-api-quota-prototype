import { useEffect, useRef } from 'react'
import { type PollingOptions, startPolling } from './polling.ts'

export function usePolling<T>(options: PollingOptions<T>): void {
  const { task, intervalMs, timeoutMs, scheduler, onSuccess, onFailure } = options
  const handlers = useRef({ onSuccess, onFailure })

  useEffect(() => {
    handlers.current = { onSuccess, onFailure }
  })

  useEffect(
    () =>
      startPolling({
        task,
        intervalMs,
        timeoutMs,
        scheduler,
        onSuccess: (result) => handlers.current.onSuccess(result),
        onFailure: (error) => handlers.current.onFailure(error),
      }),
    [task, intervalMs, timeoutMs, scheduler],
  )
}
