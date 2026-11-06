import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TrafficPanel } from '../../../src/ui/components/TrafficPanel.tsx'
import { minuteOfTraffic } from '../../support/snapshots.ts'

function figures(): HTMLElement {
  const list = document.querySelector<HTMLElement>('dl.figures')
  if (list === null) {
    throw new Error('figures list is not rendered')
  }
  return list
}

describe('TrafficPanel', () => {
  it('shows_newest_second_allowed_and_rejected', () => {
    const perSecond = minuteOfTraffic(1_000, (secondsAgo) =>
      secondsAgo === 0 ? { allowed: 183, rejected: 12 } : { allowed: 90, rejected: 0 },
    )

    render(<TrafficPanel perSecond={perSecond} />)

    expect(figures()).toHaveTextContent('Allowed 183 req/s')
    expect(figures()).toHaveTextContent('Rejected 12 req/s')
  })

  it('shows_zero_for_quiet_second', () => {
    render(<TrafficPanel perSecond={minuteOfTraffic(1_000, () => ({ allowed: 0, rejected: 0 }))} />)

    expect(figures()).toHaveTextContent('Allowed 0 req/s')
    expect(figures()).toHaveTextContent('Rejected 0 req/s')
  })

  it('shows_dash_without_entries', () => {
    render(<TrafficPanel perSecond={[]} />)

    expect(figures()).toHaveTextContent('Allowed —')
    expect(figures()).toHaveTextContent('Rejected —')
    expect(figures()).not.toHaveTextContent(/\d/)
  })

  it('includes_trend', () => {
    render(<TrafficPanel perSecond={minuteOfTraffic(1_000, () => ({ allowed: 5, rejected: 1 }))} />)

    expect(screen.getByRole('img', { name: /last 60 seconds/ })).toBeInTheDocument()
  })
})
