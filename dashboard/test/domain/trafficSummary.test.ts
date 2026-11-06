import { describe, expect, it } from 'vitest'
import type { SecondTraffic } from '../../src/domain/snapshot.ts'
import { currentSecondOf, trafficPeaksOf } from '../../src/domain/trafficSummary.ts'

const seconds: SecondTraffic[] = [
  { second: 100, allowed: 120, rejected: 3 },
  { second: 101, allowed: 200, rejected: 1 },
  { second: 102, allowed: 183, rejected: 12 },
]

describe('currentSecondOf', () => {
  it('current_second_is_newest_entry', () => {
    expect(currentSecondOf(seconds)).toEqual({ second: 102, allowed: 183, rejected: 12 })
  })

  it('current_second_is_null_without_entries', () => {
    expect(currentSecondOf([])).toBeNull()
  })
})

describe('trafficPeaksOf', () => {
  it('peaks_are_max_of_each_series', () => {
    expect(trafficPeaksOf(seconds)).toEqual({ allowed: 200, rejected: 12 })
  })

  it('peaks_are_zero_without_entries', () => {
    expect(trafficPeaksOf([])).toEqual({ allowed: 0, rejected: 0 })
  })
})
