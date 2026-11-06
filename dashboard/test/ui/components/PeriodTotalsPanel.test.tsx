import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PeriodTotalsPanel } from '../../../src/ui/components/PeriodTotalsPanel.tsx'
import { metricsWith } from '../../support/snapshots.ts'

function figures(): HTMLElement {
  const list = document.querySelector<HTMLElement>('dl.figures')
  if (list === null) {
    throw new Error('figures list is not rendered')
  }
  return list
}

describe('PeriodTotalsPanel', () => {
  it('shows_totals_with_thousands_separator', () => {
    render(<PeriodTotalsPanel metrics={metricsWith({ totals: { allowed: 12_045, rejected: 731 } })} />)

    expect(figures()).toHaveTextContent('Allowed 12,045')
    expect(figures()).toHaveTextContent('Rejected 731')
  })

  it('shows_near_and_over_counts', () => {
    render(<PeriodTotalsPanel metrics={metricsWith({ tenantsNear: 2, tenantsOver: 1 })} />)

    expect(figures()).toHaveTextContent('Near limit 2')
    expect(figures()).toHaveTextContent('Over limit 1')
  })

  it('shows_zeros_for_new_period', () => {
    render(
      <PeriodTotalsPanel metrics={metricsWith({ totals: { allowed: 0, rejected: 0 }, tenantsNear: 0, tenantsOver: 0 })} />,
    )

    for (const figure of ['Allowed 0', 'Rejected 0', 'Near limit 0', 'Over limit 0']) {
      expect(figures()).toHaveTextContent(figure)
    }
  })
})
