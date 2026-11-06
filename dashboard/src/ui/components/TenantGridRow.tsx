import type { GridRow } from '../../domain/tenantGrid.ts'
import {
  formatOptionalCount,
  formatUsage,
  type StatusTone,
  tenantStatusLabelOf,
  tierChangeFailureText,
} from '../format.ts'
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
      <td className="numeric tenant-id">{row.id}</td>
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
      <td>
        {row.usagePercent === null ? (
          <span className="numeric">{formatUsage(row.used, row.limit, row.usagePercent)}</span>
        ) : (
          <div className="usage">
            <span className="numeric">{formatUsage(row.used, row.limit, row.usagePercent)}</span>
            <UsageMeter percent={row.usagePercent} tone={status.tone} />
          </div>
        )}
      </td>
      <td className="numeric">{formatOptionalCount(row.rejected)}</td>
      <td>
        <span className={`tenant-status status-${status.tone}`}>{status.text}</span>
      </td>
    </tr>
  )
}

function UsageMeter({ percent, tone }: { percent: number; tone: StatusTone }) {
  return (
    <div className="meter" aria-hidden="true">
      <div className={`meter-fill meter-${tone}`} style={{ width: `${Math.min(Math.max(percent, 0), 100)}%` }} />
    </div>
  )
}
