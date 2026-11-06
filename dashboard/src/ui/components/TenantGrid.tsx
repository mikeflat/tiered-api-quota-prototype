import type { RowsGrid, TenantGridView } from '../../domain/tenantGrid.ts'
import { formatCount } from '../format.ts'
import { Pager } from './Pager.tsx'
import { TenantGridRow } from './TenantGridRow.tsx'

interface TenantGridProps {
  readonly grid: TenantGridView
  readonly onTierChange: (tenantId: number, tier: string) => void
  readonly onPrevious: () => void
  readonly onNext: () => void
  readonly onClearStatus: () => void
}

const columns = ['Tenant', 'ID', 'Tier', 'Used', 'Rejected', 'Status']

export function TenantGrid({ grid, onTierChange, onPrevious, onNext, onClearStatus }: TenantGridProps) {
  if (grid.kind === 'unavailable') {
    return (
      <div className="rows-unavailable">
        <p className="muted">Tenant rows unavailable: the status filter needs metrics</p>
        <button type="button" disabled={!grid.canClearStatus} onClick={onClearStatus}>
          Clear status filter
        </button>
      </div>
    )
  }
  return (
    <>
      {grid.updating && (
        <p className="updating muted" role="status">
          Updating…
        </p>
      )}
      <div className={grid.updating ? 'tenant-grid-body is-updating' : 'tenant-grid-body'} data-testid="tenant-grid-body">
        {grid.rows.length === 0 ? <p className="muted">No tenants</p> : <RowsTable grid={grid} onTierChange={onTierChange} />}
      </div>
      <div className="grid-footer">
        {grid.rows.length > 0 && (
          <p className="grid-count muted">
            Showing {formatCount(grid.shownCount)} of {formatCount(grid.total)} tenants
          </p>
        )}
        <Pager
          pageNumber={grid.pageNumber}
          pageCount={grid.pageCount}
          canPrevious={grid.canPrevious}
          canNext={grid.canNext}
          onPrevious={onPrevious}
          onNext={onNext}
        />
      </div>
    </>
  )
}

function RowsTable({ grid, onTierChange }: { grid: RowsGrid; onTierChange: TenantGridProps['onTierChange'] }) {
  return (
    <div className="tenant-grid-scroll">
      <table className="tenant-grid">
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column} scope="col">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {grid.rows.map((row) => (
            <TenantGridRow key={row.id} row={row} onTierChange={onTierChange} />
          ))}
        </tbody>
      </table>
    </div>
  )
}
