import { describe, expect, it } from 'vitest'
import { enforcementStatusOf } from '../../src/domain/enforcement.ts'

describe('enforcementStatusOf', () => {
  it('redis_down_is_degraded', () => {
    expect(enforcementStatusOf({ redisStatus: 'DOWN' })).toBe('degraded')
  })

  it('redis_up_is_ok', () => {
    expect(enforcementStatusOf({ redisStatus: 'UP' })).toBe('ok')
  })

  it('missing_redis_component_is_unknown', () => {
    expect(enforcementStatusOf({ redisStatus: null })).toBe('unknown')
  })

  it('unexpected_redis_status_is_unknown', () => {
    expect(enforcementStatusOf({ redisStatus: 'OUT_OF_SERVICE' })).toBe('unknown')
  })
})
