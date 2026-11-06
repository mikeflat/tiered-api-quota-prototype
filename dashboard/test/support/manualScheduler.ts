import type { Cancel, Scheduler } from '../../src/application/ports.ts'

interface ScheduledCallback {
  readonly id: number
  readonly dueAt: number
  readonly callback: () => void
}

export class ManualScheduler implements Scheduler {
  private currentTime: number
  private nextId = 0
  private pending: ScheduledCallback[] = []

  constructor(startTime = 0) {
    this.currentTime = startTime
  }

  now(): number {
    return this.currentTime
  }

  schedule(callback: () => void, delayMs: number): Cancel {
    const id = this.nextId++
    this.pending.push({ id, dueAt: this.currentTime + Math.max(0, delayMs), callback })
    return () => {
      this.pending = this.pending.filter((scheduled) => scheduled.id !== id)
    }
  }

  advanceBy(millis: number): void {
    const target = this.currentTime + millis
    let due = this.nextDueBy(target)
    while (due !== undefined) {
      this.pending = this.pending.filter((scheduled) => scheduled.id !== due!.id)
      this.currentTime = due.dueAt
      due.callback()
      due = this.nextDueBy(target)
    }
    this.currentTime = target
  }

  pendingCount(): number {
    return this.pending.length
  }

  private nextDueBy(target: number): ScheduledCallback | undefined {
    return this.pending
      .filter((scheduled) => scheduled.dueAt <= target)
      .sort((a, b) => a.dueAt - b.dueAt || a.id - b.id)[0]
  }
}
