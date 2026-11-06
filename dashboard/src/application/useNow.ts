import { useEffect, useState } from 'react'
import type { Scheduler } from './ports.ts'

export function useNow(scheduler: Scheduler, tickMs: number): number {
  const [now, setNow] = useState(() => scheduler.now())

  useEffect(() => {
    let cancelTick = scheduler.schedule(function tick() {
      setNow(scheduler.now())
      cancelTick = scheduler.schedule(tick, tickMs)
    }, tickMs)
    return () => cancelTick()
  }, [scheduler, tickMs])

  return now
}
