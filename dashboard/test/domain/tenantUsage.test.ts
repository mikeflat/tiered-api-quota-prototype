import { describe, expect, it } from 'vitest'
import { usagePercent } from '../../src/domain/tenantUsage.ts'

describe('usagePercent', () => {
  it('percent_is_rounded_down', () => {
    expect(usagePercent(85, 100)).toBe(85)
    expect(usagePercent(999, 1000)).toBe(99)
  })

  it('overshoot_exceeds_one_hundred', () => {
    expect(usagePercent(103, 100)).toBe(103)
  })

  it('null_usage_has_no_percent', () => {
    expect(usagePercent(null, 100)).toBeNull()
  })

  it('non_positive_limit_has_no_percent', () => {
    expect(usagePercent(5, 0)).toBeNull()
  })
})
