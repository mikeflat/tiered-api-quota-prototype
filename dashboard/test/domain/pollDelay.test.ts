import { describe, expect, it } from 'vitest'
import { nextPollDelay } from '../../src/domain/pollDelay.ts'

describe('nextPollDelay', () => {
  it('fast_poll_waits_rest_of_interval', () => {
    expect(nextPollDelay(1000, 5000, 5200)).toBe(800)
  })

  it('slow_poll_runs_next_immediately', () => {
    expect(nextPollDelay(1000, 5000, 6000)).toBe(0)
  })

  it('delay_is_never_negative', () => {
    expect(nextPollDelay(1000, 5000, 7500)).toBe(0)
  })
})
