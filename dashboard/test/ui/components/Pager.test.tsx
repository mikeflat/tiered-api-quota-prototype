import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Pager } from '../../../src/ui/components/Pager.tsx'

function renderPager(position: { pageNumber: number; pageCount: number; canPrevious: boolean; canNext: boolean }) {
  const onPrevious = vi.fn()
  const onNext = vi.fn()
  render(<Pager {...position} onPrevious={onPrevious} onNext={onNext} />)
  return { onPrevious, onNext }
}

describe('Pager', () => {
  it('shows_page_of_count', () => {
    renderPager({ pageNumber: 1, pageCount: 2, canPrevious: false, canNext: true })

    expect(screen.getByRole('navigation', { name: 'Tenant pages' })).toHaveTextContent('Page 1 of 2')
  })

  it('previous_disabled_on_first_page', () => {
    renderPager({ pageNumber: 1, pageCount: 2, canPrevious: false, canNext: true })

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
  })

  it('next_disabled_on_last_page', () => {
    renderPager({ pageNumber: 2, pageCount: 2, canPrevious: true, canNext: false })

    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('buttons_report_clicks', async () => {
    const { onPrevious, onNext } = renderPager({ pageNumber: 2, pageCount: 3, canPrevious: true, canNext: true })

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }))

    expect(onNext).toHaveBeenCalledOnce()
    expect(onPrevious).toHaveBeenCalledOnce()
  })

  it('disabled_pager_disables_both', () => {
    renderPager({ pageNumber: 2, pageCount: 3, canPrevious: false, canNext: false })

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })
})
