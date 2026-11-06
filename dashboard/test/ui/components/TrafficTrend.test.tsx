import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TrafficTrend } from '../../../src/ui/components/TrafficTrend.tsx'
import { minuteOfTraffic } from '../../support/snapshots.ts'

function pointCountOf(line: Element | null): number {
  return (line?.getAttribute('points') ?? '').split(' ').filter((point) => point !== '').length
}

describe('TrafficTrend', () => {
  it('trend_is_an_image_named_with_last_sixty_seconds_and_peaks', () => {
    const perSecond = minuteOfTraffic(1_000, (secondsAgo) =>
      secondsAgo === 5 ? { allowed: 200, rejected: 50 } : { allowed: 10, rejected: 1 },
    )

    render(<TrafficTrend perSecond={perSecond} />)

    expect(screen.getByRole('img')).toHaveAccessibleName(
      'Requests per second over the last 60 seconds, peak 200 allowed and 50 rejected',
    )
  })

  it('draws_sixty_points_per_line', () => {
    const { container } = render(<TrafficTrend perSecond={minuteOfTraffic(1_000, () => ({ allowed: 5, rejected: 1 }))} />)

    expect(pointCountOf(container.querySelector('.trend-line-allowed'))).toBe(60)
    expect(pointCountOf(container.querySelector('.trend-line-rejected'))).toBe(60)
  })

  it('legend_names_allowed_and_rejected', () => {
    render(<TrafficTrend perSecond={minuteOfTraffic(1_000, () => ({ allowed: 5, rejected: 1 }))} />)

    expect(screen.getByText('Allowed')).toBeInTheDocument()
    expect(screen.getByText('Rejected')).toBeInTheDocument()
  })

  it('all_zero_minute_renders_flat_lines', () => {
    const { container } = render(<TrafficTrend perSecond={minuteOfTraffic(1_000, () => ({ allowed: 0, rejected: 0 }))} />)

    const allowedPoints = (container.querySelector('.trend-line-allowed')?.getAttribute('points') ?? '').split(' ')
    expect(allowedPoints.every((point) => point.endsWith(',120'))).toBe(true)
    expect(screen.getByRole('img')).toHaveAccessibleName(/peak 0 allowed and 0 rejected/)
  })
})
