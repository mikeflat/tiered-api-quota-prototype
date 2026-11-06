import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
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

describe('TrafficTrend hover', () => {
  function hoverAt(container: HTMLElement, clientX: number) {
    const plot = container.querySelector('.trend-plot')!
    vi.spyOn(plot, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 590, 200))
    fireEvent.pointerMove(plot, { clientX })
    return plot
  }

  it('shows_values_of_the_hovered_second', () => {
    const perSecond = minuteOfTraffic(1_000, (secondsAgo) =>
      secondsAgo === 0 ? { allowed: 1_234, rejected: 56 } : { allowed: 10, rejected: 1 },
    )
    const { container } = render(<TrafficTrend perSecond={perSecond} />)

    hoverAt(container, 590)

    const tooltip = container.querySelector('.trend-tooltip')
    expect(tooltip).toHaveTextContent('now')
    expect(tooltip).toHaveTextContent('Allowed 1,234')
    expect(tooltip).toHaveTextContent('Rejected 56')
  })

  it('labels_older_seconds_and_hides_on_leave', () => {
    const { container } = render(<TrafficTrend perSecond={minuteOfTraffic(1_000, () => ({ allowed: 5, rejected: 1 }))} />)

    const plot = hoverAt(container, 0)
    expect(container.querySelector('.trend-tooltip')).toHaveTextContent('−59s')

    fireEvent.pointerLeave(plot)
    expect(container.querySelector('.trend-tooltip')).not.toBeInTheDocument()
  })
})
