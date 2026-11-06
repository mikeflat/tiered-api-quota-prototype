import type { TenantGridView } from '../../domain/tenantGrid.ts'
import { formatCount } from '../format.ts'
import { TenantGridRow } from './TenantGridRow.tsx'

interface TenantGridProps {
  readonly grid: TenantGridView
  readonly onTierChange: (tenantId: number, tier: string) => void
}

const columns = ['Tenant', 'ID', 'Tier', 'Used', 'Rejected', 'Status']

export function TenantGrid({ grid, onTierChange }: TenantGridProps) {
  if (grid.kind === 'unavailable') {
    return <p className="muted">Tenant rows unavailable</p>
  }
  if (grid.rows.length === 0) {
    return <p className="muted">No tenants</p>
  }
  return (
    <>
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
      <p className="grid-count muted">
        Showing {formatCount(grid.shownCount)} of {formatCount(grid.total)} tenants
      </p>
    </>
  )
}
