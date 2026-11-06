import { formatCount } from '../format.ts'

interface PagerProps {
  readonly pageNumber: number
  readonly pageCount: number
  readonly canPrevious: boolean
  readonly canNext: boolean
  readonly onPrevious: () => void
  readonly onNext: () => void
}

export function Pager({ pageNumber, pageCount, canPrevious, canNext, onPrevious, onNext }: PagerProps) {
  return (
    <nav className="pager" aria-label="Tenant pages">
      <button type="button" disabled={!canPrevious} onClick={onPrevious}>
        Previous
      </button>
      <span className="pager-position">
        Page {formatCount(pageNumber)} of {formatCount(pageCount)}
      </span>
      <button type="button" disabled={!canNext} onClick={onNext}>
        Next
      </button>
    </nav>
  )
}
