import { describe, expect, it } from 'vitest'
import {
  connectionLabelOf,
  enforcementLabelOf,
  formatCount,
  formatCountdown,
  formatOptionalCount,
  formatUsage,
  tenantStatusLabelOf,
  tierChangeFailureText,
  tierOptionLabel,
} from '../../src/ui/format.ts'

describe('formatCount', () => {
  it('counts_use_thousands_separator', () => {
    expect(formatCount(12_045)).toBe('12,045')
    expect(formatCount(1_234_567)).toBe('1,234,567')
    expect(formatCount(731)).toBe('731')
    expect(formatCount(0)).toBe('0')
  })
})

describe('formatCountdown', () => {
  it('countdown_under_an_hour_is_minutes_and_seconds', () => {
    expect(formatCountdown(15)).toBe('0:15')
    expect(formatCountdown(60)).toBe('1:00')
    expect(formatCountdown(3599)).toBe('59:59')
  })

  it('countdown_from_an_hour_includes_hours', () => {
    expect(formatCountdown(3600)).toBe('1:00:00')
    expect(formatCountdown(3725)).toBe('1:02:05')
    expect(formatCountdown(86_400)).toBe('24:00:00')
  })

  it('zero_countdown_is_zero_seconds', () => {
    expect(formatCountdown(0)).toBe('0:00')
  })

  it('missing_countdown_is_a_dash', () => {
    expect(formatCountdown(null)).toBe('—')
  })
})

describe('status labels', () => {
  it('status_labels_match_header_texts', () => {
    expect(connectionLabelOf('connecting')).toEqual({ text: 'Connecting', tone: 'neutral' })
    expect(connectionLabelOf('connected')).toEqual({ text: 'Connected', tone: 'ok' })
    expect(connectionLabelOf('unreachable')).toEqual({ text: 'Unreachable', tone: 'error' })
    expect(enforcementLabelOf('unknown')).toEqual({ text: 'Unknown', tone: 'neutral' })
    expect(enforcementLabelOf('ok')).toEqual({ text: 'OK', tone: 'ok' })
    expect(enforcementLabelOf('degraded')).toEqual({ text: 'Degraded', tone: 'warning' })
  })
})

describe('tenant grid formatting', () => {
  it('usage_shows_used_limit_and_percent', () => {
    expect(formatUsage(85, 100, 85)).toBe('85 / 100 (85%)')
    expect(formatUsage(103, 100, 103)).toBe('103 / 100 (103%)')
  })

  it('usage_uses_thousands_separator', () => {
    expect(formatUsage(999, 1000, 99)).toBe('999 / 1,000 (99%)')
    expect(formatOptionalCount(1240)).toBe('1,240')
  })

  it('missing_usage_is_a_dash', () => {
    expect(formatUsage(null, 100, null)).toBe('—')
    expect(formatOptionalCount(null)).toBe('—')
  })

  it('tenant_status_labels', () => {
    expect(tenantStatusLabelOf('ok')).toEqual({ text: 'OK', tone: 'ok' })
    expect(tenantStatusLabelOf('near')).toEqual({ text: 'Near', tone: 'warning' })
    expect(tenantStatusLabelOf('over')).toEqual({ text: 'Over', tone: 'error' })
    expect(tenantStatusLabelOf(null)).toEqual({ text: '—', tone: 'neutral' })
  })

  it('tier_option_label_includes_limit', () => {
    expect(tierOptionLabel({ name: 'Silver', limit: 1000 })).toBe('Silver (1,000)')
    expect(tierOptionLabel({ name: 'Bronze', limit: 100 })).toBe('Bronze (100)')
  })

  it('failure_texts_match_spec', () => {
    expect(tierChangeFailureText({ kind: 'notFound' })).toBe('Tier change failed: tenant not found')
    expect(tierChangeFailureText({ kind: 'rejected' })).toBe('Tier change failed: tier rejected by the gateway')
    expect(tierChangeFailureText({ kind: 'serverError', status: 502 })).toBe('Tier change failed: gateway error (502)')
    expect(tierChangeFailureText({ kind: 'timedOut' })).toBe('Tier change failed: no answer within 2 s')
    expect(tierChangeFailureText({ kind: 'unreachable' })).toBe('Tier change failed: gateway unreachable')
  })
})
