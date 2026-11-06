import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { FilterControlsView } from '../../../src/domain/filterControls.ts'
import { TenantFilters } from '../../../src/ui/components/TenantFilters.tsx'
import { recordingActions } from '../../support/tenantQueryActions.ts'

const controls: FilterControlsView = {
  nameText: '',
  idText: '',
  idInvalid: false,
  tier: null,
  status: null,
  tierOptions: ['Bronze', 'Silver', 'Gold'],
  enabled: true,
  statusEnabled: true,
}

function renderFilters(overrides: Partial<FilterControlsView> = {}) {
  const actions = recordingActions()
  render(<TenantFilters controls={{ ...controls, ...overrides }} actions={actions} />)
  return actions
}

function optionTexts(select: HTMLElement): (string | null)[] {
  return within(select).getAllByRole('option').map((option) => option.textContent)
}

describe('TenantFilters', () => {
  it('shows_labelled_controls_and_clear_button', () => {
    renderFilters()

    expect(screen.getByRole('searchbox', { name: 'Name' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'ID' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Tier' })).toBeInTheDocument()
    expect(optionTexts(screen.getByRole('combobox', { name: 'Status' }))).toEqual([
      'All statuses',
      'OK',
      'Near',
      'Over',
    ])
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeEnabled()
  })

  it('tier_offers_all_and_loaded_tiers', () => {
    renderFilters()

    expect(optionTexts(screen.getByRole('combobox', { name: 'Tier' }))).toEqual(['All tiers', 'Bronze', 'Silver', 'Gold'])
  })

  it('typing_reports_text', () => {
    const actions = renderFilters()

    fireEvent.change(screen.getByRole('searchbox', { name: 'Name' }), { target: { value: 'bank' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'ID' }), { target: { value: '3' } })

    expect(actions.setNameText).toHaveBeenCalledWith('bank')
    expect(actions.setIdText).toHaveBeenCalledWith('3')
  })

  it('choosing_tier_and_status_reports_choice', async () => {
    const actions = renderFilters({ tier: 'Gold' })

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Over')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Tier' }), 'All tiers')
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    expect(actions.setStatus).toHaveBeenCalledWith('over')
    expect(actions.setTier).toHaveBeenCalledWith(null)
    expect(actions.clearFilters).toHaveBeenCalledOnce()
  })

  it('invalid_id_shows_message', () => {
    renderFilters({ idText: 'acme', idInvalid: true })

    const id = screen.getByRole('textbox', { name: 'ID' })
    expect(id).toHaveAttribute('aria-invalid', 'true')
    expect(id).toHaveAccessibleDescription('ID must be a whole number from 1')
  })

  it('disabled_controls_keep_values', () => {
    renderFilters({ nameText: 'bank', tier: 'Gold', enabled: false, statusEnabled: false })

    expect(screen.getByRole('searchbox', { name: 'Name' })).toBeDisabled()
    expect(screen.getByRole('searchbox', { name: 'Name' })).toHaveValue('bank')
    expect(screen.getByRole('textbox', { name: 'ID' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Tier' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Tier' })).toHaveValue('Gold')
    expect(screen.getByRole('combobox', { name: 'Status' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeDisabled()
  })

  it('status_disabled_without_metrics_shows_value', () => {
    renderFilters({ status: 'over', statusEnabled: false })

    expect(screen.getByRole('combobox', { name: 'Status' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveValue('over')
    expect(screen.getByRole('combobox', { name: 'Tier' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeEnabled()
  })
})
