import type { ConnectionStatus } from '../../domain/dashboardView.ts'
import type { EnforcementStatus } from '../../domain/enforcement.ts'
import { connectionLabelOf, enforcementLabelOf, formatCountdown, type StatusLabel } from '../format.ts'

interface HeaderStatusProps {
  readonly connection: ConnectionStatus
  readonly enforcement: EnforcementStatus
  readonly secondsUntilReset: number | null
}

export function HeaderStatus({ connection, enforcement, secondsUntilReset }: HeaderStatusProps) {
  return (
    <dl className="header-status" aria-label="Status">
      <StatusItem term="Gateway" label={connectionLabelOf(connection)} />
      <StatusItem term="Enforcement" label={enforcementLabelOf(enforcement)} />
      <div className="header-status-item">
        <dt>Resets in</dt> <dd className="countdown">{formatCountdown(secondsUntilReset)}</dd>
      </div>
    </dl>
  )
}

function StatusItem({ term, label }: { term: string; label: StatusLabel }) {
  return (
    <div className="header-status-item">
      <dt>{term}</dt> <dd className={`status status-${label.tone}`}>{label.text}</dd>
    </div>
  )
}
