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
        <Chevron path="m15 18-6-6 6-6" />
        Previous
      </button>
      <span className="pager-position">
        Page {formatCount(pageNumber)} of {formatCount(pageCount)}
      </span>
      <button type="button" disabled={!canNext} onClick={onNext}>
        Next
        <Chevron path="m9 18 6-6-6-6" />
      </button>
    </nav>
  )
}

function Chevron({ path }: { path: string }) {
  return (
    <svg className="pager-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={path} />
    </svg>
  )
}
