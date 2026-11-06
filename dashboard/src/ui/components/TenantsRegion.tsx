import type { TenantQueryActions } from '../../application/useTenantQuery.ts'
import type { FilterControlsView } from '../../domain/filterControls.ts'
import type { TenantGridView } from '../../domain/tenantGrid.ts'
import { TenantFilters } from './TenantFilters.tsx'
import { TenantGrid } from './TenantGrid.tsx'

export interface TenantsRegionModel {
  readonly grid: TenantGridView | null
  readonly filters: FilterControlsView
  readonly actions: TenantQueryActions
  readonly onTierChange: (tenantId: number, tier: string) => void
}

export function TenantsRegion({ tenants }: { tenants: TenantsRegionModel }) {
  if (tenants.grid === null) {
    return null
  }
  return (
    <>
      <TenantFilters controls={tenants.filters} actions={tenants.actions} />
      <TenantGrid
        grid={tenants.grid}
        onTierChange={tenants.onTierChange}
        onPrevious={tenants.actions.previousPage}
        onNext={tenants.actions.nextPage}
        onClearStatus={tenants.actions.clearStatus}
      />
    </>
  )
}
