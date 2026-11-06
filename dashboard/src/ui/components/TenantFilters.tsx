import type { TenantQueryActions } from '../../application/useTenantQuery.ts'
import type { FilterControlsView } from '../../domain/filterControls.ts'
import type { TenantStatus } from '../../domain/snapshot.ts'

interface TenantFiltersProps {
  readonly controls: FilterControlsView
  readonly actions: TenantQueryActions
}

const idErrorId = 'id-filter-error'

const statusOptions: readonly { readonly value: TenantStatus; readonly label: string }[] = [
  { value: 'ok', label: 'OK' },
  { value: 'near', label: 'Near' },
  { value: 'over', label: 'Over' },
]

export function TenantFilters({ controls, actions }: TenantFiltersProps) {
  return (
    <form className="tenant-filters" aria-label="Tenant filters" onSubmit={(event) => event.preventDefault()}>
      <label className="filter">
        <span>Name</span>
        <input
          type="search"
          value={controls.nameText}
          disabled={!controls.enabled}
          onChange={(event) => actions.setNameText(event.target.value)}
        />
      </label>
      <div className="filter">
        <label className="filter-label">
          <span>ID</span>
          <input
            type="text"
            inputMode="numeric"
            className="id-filter"
            value={controls.idText}
            disabled={!controls.enabled}
            aria-invalid={controls.idInvalid}
            aria-describedby={controls.idInvalid ? idErrorId : undefined}
            onChange={(event) => actions.setIdText(event.target.value)}
          />
        </label>
        {controls.idInvalid && (
          <span id={idErrorId} className="filter-error">
            ID must be a whole number from 1
          </span>
        )}
      </div>
      <label className="filter">
        <span>Tier</span>
        <select
          value={controls.tier ?? ''}
          disabled={!controls.enabled}
          onChange={(event) => actions.setTier(event.target.value === '' ? null : event.target.value)}
        >
          <option value="">All tiers</option>
          {controls.tierOptions.map((tier) => (
            <option key={tier} value={tier}>
              {tier}
            </option>
          ))}
        </select>
      </label>
      <label className="filter">
        <span>Status</span>
        <select
          value={controls.status ?? ''}
          disabled={!controls.statusEnabled}
          onChange={(event) => actions.setStatus(statusOf(event.target.value))}
        >
          <option value="">All statuses</option>
          {statusOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      <button type="button" className="filter-clear" disabled={!controls.enabled} onClick={actions.clearFilters}>
        Clear filters
      </button>
    </form>
  )
}

function statusOf(value: string): TenantStatus | null {
  return statusOptions.find((option) => option.value === value)?.value ?? null
}
