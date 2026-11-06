import { describe, expect, it } from 'vitest'
import { connectionLabelOf, enforcementLabelOf, formatCount, formatCountdown } from '../../src/ui/format.ts'

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
