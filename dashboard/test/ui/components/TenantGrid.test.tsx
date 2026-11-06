import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { GridRow, RowsGrid } from '../../../src/domain/tenantGrid.ts'
import { TenantGrid } from '../../../src/ui/components/TenantGrid.tsx'
import { defaultTiers } from '../../support/tiers.ts'

function gridRow(overrides: Partial<GridRow> = {}): GridRow {
  return {
    id: 1,
    name: 'Acme Corp',
    tier: 'Bronze',
    limit: 100,
    used: 85,
    rejected: 0,
    status: 'near',
    usagePercent: 85,
    tierOptions: defaultTiers,
    saving: false,
    failure: null,
    selectorEnabled: true,
    ...overrides,
  }
}

const blueBank = gridRow({
  id: 2,
  name: 'Blue Bank',
  tier: 'Silver',
  limit: 1000,
  used: 1040,
  rejected: 1240,
  status: 'over',
  usagePercent: 104,
})

function gridOf(...rows: GridRow[]): RowsGrid {
  return {
    kind: 'rows',
    rows,
    shownCount: rows.length,
    total: 15,
    pageNumber: 1,
    pageCount: 2,
    canPrevious: false,
    canNext: true,
    updating: false,
  }
}

const noPaging = { onPrevious: () => {}, onNext: () => {}, onClearStatus: () => {} }

function rowOf(name: string): HTMLElement {
  const header = screen.getByRole('rowheader', { name })
  const row = header.closest('tr')
  if (row === null) {
    throw new Error(`no row for ${name}`)
  }
  return row
}

function selectorOf(name: string): HTMLSelectElement {
  return screen.getByRole('combobox', { name: `Tier for ${name}` })
}

describe('TenantGrid', () => {
  it('shows_headers_and_rows_in_order', () => {
    render(<TenantGrid grid={gridOf(gridRow(), blueBank)} onTierChange={() => {}} {...noPaging} />)

    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Tenant',
      'ID',
      'Tier',
      'Used',
      'Rejected',
      'Status',
    ])
    expect(screen.getAllByRole('rowheader').map((header) => header.textContent)).toEqual(['Acme Corp', 'Blue Bank'])
  })

  it('shows_usage_rejected_and_status_text', () => {
    render(<TenantGrid grid={gridOf(gridRow(), blueBank)} onTierChange={() => {}} {...noPaging} />)

    expect(rowOf('Acme Corp')).toHaveTextContent('85 / 100 (85%)')
    expect(within(rowOf('Acme Corp')).getByText('Near')).toHaveClass('status-warning')
    expect(rowOf('Blue Bank')).toHaveTextContent('1,040 / 1,000 (104%)')
    expect(rowOf('Blue Bank')).toHaveTextContent('1,240')
    expect(within(rowOf('Blue Bank')).getByText('Over')).toHaveClass('status-error')
  })

  it('shows_dashes_without_usage_and_keeps_selector_enabled', () => {
    render(
      <TenantGrid
        grid={gridOf(gridRow({ used: null, rejected: null, status: null, usagePercent: null }))}
        onTierChange={() => {}} {...noPaging}
      />,
    )

    const cells = within(rowOf('Acme Corp')).getAllByRole('cell')
    expect(cells.map((cell) => cell.textContent).slice(2)).toEqual(['—', '—', '—'])
    expect(selectorOf('Acme Corp')).toBeEnabled()
  })

  it('shows_no_tenants_for_empty_page', () => {
    render(<TenantGrid grid={{ ...gridOf(), shownCount: 0, total: 0, pageCount: 1, canNext: false }} onTierChange={() => {}} {...noPaging} />)

    expect(screen.getByText('No tenants')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows_rows_unavailable_message', () => {
    render(<TenantGrid grid={{ kind: 'unavailable', canClearStatus: true }} onTierChange={() => {}} {...noPaging} />)

    expect(screen.getByText('Tenant rows unavailable: the status filter needs metrics')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows_count_of_total', () => {
    render(<TenantGrid grid={gridOf(gridRow(), blueBank)} onTierChange={() => {}} {...noPaging} />)

    expect(screen.getByText('Showing 2 of 15 tenants')).toBeInTheDocument()
  })

  it('selector_offers_tiers_with_limits_and_selects_current', () => {
    render(<TenantGrid grid={gridOf(gridRow())} onTierChange={() => {}} {...noPaging} />)

    const selector = selectorOf('Acme Corp')
    expect(within(selector).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Bronze (100)',
      'Silver (1,000)',
      'Gold (10,000)',
    ])
    expect(selector).toHaveValue('Bronze')
  })

  it('choosing_tier_reports_row_and_tier', async () => {
    const onTierChange = vi.fn()
    render(<TenantGrid grid={gridOf(gridRow(), blueBank)} onTierChange={onTierChange} {...noPaging} />)

    await userEvent.selectOptions(selectorOf('Blue Bank'), 'Gold')

    expect(onTierChange).toHaveBeenCalledWith(2, 'Gold')
  })

  it('saving_row_is_disabled_and_marked', () => {
    render(
      <TenantGrid
        grid={gridOf(gridRow({ tier: 'Gold', saving: true, selectorEnabled: false }), blueBank)}
        onTierChange={() => {}} {...noPaging}
      />,
    )

    expect(selectorOf('Acme Corp')).toBeDisabled()
    expect(selectorOf('Acme Corp')).toHaveValue('Gold')
    expect(rowOf('Acme Corp')).toHaveTextContent('Saving…')
    expect(selectorOf('Blue Bank')).toBeEnabled()
    expect(rowOf('Blue Bank')).not.toHaveTextContent('Saving…')
  })

  it('failed_row_shows_inline_error', () => {
    render(
      <TenantGrid grid={gridOf(gridRow({ failure: { kind: 'notFound' } }), blueBank)} onTierChange={() => {}} {...noPaging} />,
    )

    expect(within(rowOf('Acme Corp')).getByRole('alert')).toHaveTextContent('Tier change failed: tenant not found')
    expect(selectorOf('Acme Corp')).toHaveValue('Bronze')
    expect(within(rowOf('Blue Bank')).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('disabled_controls_disable_selectors', () => {
    render(
      <TenantGrid
        grid={gridOf(gridRow({ selectorEnabled: false }), { ...blueBank, selectorEnabled: false })}
        onTierChange={() => {}} {...noPaging}
      />,
    )

    expect(selectorOf('Acme Corp')).toBeDisabled()
    expect(selectorOf('Blue Bank')).toBeDisabled()
  })

  it('updating_grid_is_greyed_and_marked', () => {
    render(<TenantGrid grid={{ ...gridOf(gridRow()), updating: true }} onTierChange={() => {}} {...noPaging} />)

    expect(screen.getByRole('status')).toHaveTextContent('Updating…')
    expect(screen.getByTestId('tenant-grid-body')).toHaveClass('is-updating')
    expect(rowOf('Acme Corp')).toBeInTheDocument()
  })

  it('unavailable_rows_offer_clearing_status', async () => {
    const onClearStatus = vi.fn()
    render(
      <TenantGrid
        grid={{ kind: 'unavailable', canClearStatus: true }}
        onTierChange={() => {}}
        onPrevious={() => {}}
        onNext={() => {}}
        onClearStatus={onClearStatus}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Clear status filter' }))

    expect(onClearStatus).toHaveBeenCalledOnce()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  })

  it('grid_shows_pager', async () => {
    const onNext = vi.fn()
    render(
      <TenantGrid grid={gridOf(gridRow(), blueBank)} onTierChange={() => {}} {...noPaging} onNext={onNext} />,
    )

    expect(screen.getByRole('navigation', { name: 'Tenant pages' })).toHaveTextContent('Page 1 of 2')
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(onNext).toHaveBeenCalledOnce()
    expect(screen.getByTestId('tenant-grid-body')).not.toHaveClass('is-updating')
  })
})

describe('TenantGrid usage meter', () => {
  it('fills_to_usage_in_status_tone_and_caps_at_full', () => {
    const { container } = render(<TenantGrid grid={gridOf(gridRow(), blueBank)} onTierChange={() => {}} {...noPaging} />)

    const fills = [...container.querySelectorAll<HTMLElement>('.meter-fill')]
    expect(fills.map((fill) => fill.style.width)).toEqual(['85%', '100%'])
    expect(fills[0]).toHaveClass('meter-warning')
    expect(fills[1]).toHaveClass('meter-error')
  })

  it('has_no_meter_without_usage', () => {
    const { container } = render(
      <TenantGrid
        grid={gridOf(gridRow({ used: null, rejected: null, status: null, usagePercent: null }))}
        onTierChange={() => {}} {...noPaging}
      />,
    )

    expect(container.querySelector('.meter')).not.toBeInTheDocument()
  })
})
