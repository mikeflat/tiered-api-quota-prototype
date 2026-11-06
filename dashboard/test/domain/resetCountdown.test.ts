import { describe, expect, it } from 'vitest'
import { secondsUntilReset } from '../../src/domain/resetCountdown.ts'
import type { QuotaPeriod } from '../../src/domain/snapshot.ts'

function periodWithSecondsLeft(secondsLeft: number): QuotaPeriod {
  return { index: 100_000, endsAt: '1970-03-11T10:41:00Z', secondsLeft }
}

describe('secondsUntilReset', () => {
  it('fresh_snapshot_returns_seconds_left', () => {
    expect(secondsUntilReset(periodWithSecondsLeft(15), 10_000, 10_000)).toBe(15)
  })

  it('subtracts_whole_seconds_since_receipt', () => {
    expect(secondsUntilReset(periodWithSecondsLeft(15), 10_000, 11_000)).toBe(14)
    expect(secondsUntilReset(periodWithSecondsLeft(15), 10_000, 13_000)).toBe(12)
  })

  it('partial_second_is_not_subtracted', () => {
    expect(secondsUntilReset(periodWithSecondsLeft(15), 10_000, 10_999)).toBe(15)
  })

  it('never_goes_below_zero', () => {
    expect(secondsUntilReset(periodWithSecondsLeft(1), 10_000, 13_000)).toBe(0)
  })
})
