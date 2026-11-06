import type { GridRow } from '../../domain/tenantGrid.ts'
import { formatOptionalCount, formatUsage, tenantStatusLabelOf, tierChangeFailureText } from '../format.ts'
import { TierSelector } from './TierSelector.tsx'

interface TenantGridRowProps {
  readonly row: GridRow
  readonly onTierChange: (tenantId: number, tier: string) => void
}

export function TenantGridRow({ row, onTierChange }: TenantGridRowProps) {
  const status = tenantStatusLabelOf(row.status)
  return (
    <tr>
      <th scope="row">{row.name}</th>
      <td className="numeric">{row.id}</td>
      <td>
        <div className="tier-cell">
          <TierSelector
            tenantName={row.name}
            tier={row.tier}
            options={row.tierOptions}
            enabled={row.selectorEnabled}
            onChange={(tier) => onTierChange(row.id, tier)}
          />
          {row.saving && <span className="saving muted">Saving…</span>}
        </div>
        {row.failure !== null && (
          <p className="row-error" role="alert">
            {tierChangeFailureText(row.failure)}
          </p>
        )}
      </td>
      <td className="numeric">{formatUsage(row.used, row.limit, row.usagePercent)}</td>
      <td className="numeric">{formatOptionalCount(row.rejected)}</td>
      <td>
        <span className={`tenant-status status-${status.tone}`}>{status.text}</span>
      </td>
    </tr>
  )
}
