import type { TierOption } from '../../domain/tiers.ts'
import { tierOptionLabel } from '../format.ts'

interface TierSelectorProps {
  readonly tenantName: string
  readonly tier: string
  readonly options: readonly TierOption[]
  readonly enabled: boolean
  readonly onChange: (tier: string) => void
}

export function TierSelector({ tenantName, tier, options, enabled, onChange }: TierSelectorProps) {
  return (
    <select
      className="tier-selector"
      aria-label={`Tier for ${tenantName}`}
      value={tier}
      disabled={!enabled}
      onChange={(event) => onChange(event.target.value)}
    >
      {options.map((option) => (
        <option key={option.name} value={option.name}>
          {tierOptionLabel(option)}
        </option>
      ))}
    </select>
  )
}
