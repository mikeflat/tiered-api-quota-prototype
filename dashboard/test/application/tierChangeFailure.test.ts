import { describe, expect, it } from 'vitest'
import { RequestTimedOut } from '../../src/application/requestWithTimeout.ts'
import { tierChangeFailureOf } from '../../src/application/tierChangeFailure.ts'
import { TierChangeRefused } from '../../src/domain/tierChange.ts'

describe('tierChangeFailureOf', () => {
  it('timeout_is_timed_out', () => {
    expect(tierChangeFailureOf(new RequestTimedOut(2_000))).toEqual({ kind: 'timedOut' })
  })

  it('status_404_is_not_found', () => {
    expect(tierChangeFailureOf(new TierChangeRefused(404))).toEqual({ kind: 'notFound' })
  })

  it('status_400_is_rejected', () => {
    expect(tierChangeFailureOf(new TierChangeRefused(400))).toEqual({ kind: 'rejected' })
  })

  it('other_status_is_server_error_with_status', () => {
    expect(tierChangeFailureOf(new TierChangeRefused(502))).toEqual({ kind: 'serverError', status: 502 })
    expect(tierChangeFailureOf(new TierChangeRefused(409))).toEqual({ kind: 'serverError', status: 409 })
  })

  it('network_error_is_unreachable', () => {
    expect(tierChangeFailureOf(new TypeError('Failed to fetch'))).toEqual({ kind: 'unreachable' })
  })
})
