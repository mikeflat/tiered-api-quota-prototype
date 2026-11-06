import type { QuotaPeriod } from './snapshot.ts'

export function secondsUntilReset(period: QuotaPeriod, receivedAt: number, now: number): number {
  const wholeSecondsSinceReceipt = Math.max(0, Math.floor((now - receivedAt) / 1000))
  return Math.max(0, period.secondsLeft - wholeSecondsSinceReceipt)
}
