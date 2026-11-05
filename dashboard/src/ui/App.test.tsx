import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App.tsx'

describe('App shell', () => {
  it('renders the "Quota dashboard" heading', () => {
    render(<App />)

    expect(screen.getByRole('heading', { level: 1, name: 'Quota dashboard' })).toBeInTheDocument()
  })

  it('shows no numbers in main, so nothing pretends to be data', () => {
    render(<App />)

    const main = screen.getByRole('main')
    expect(main).not.toHaveTextContent(/\d/)
  })
})
