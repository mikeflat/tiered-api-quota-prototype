## MODIFIED Requirements

### Requirement: Tier controls are disabled while unreachable
While the gateway is unreachable or no snapshot has arrived yet, the tier selectors in the tenant grid SHALL be disabled; in every other state, including metrics unavailable and enforcement degraded, they SHALL be enabled (FR-21). A selector whose own tier change is in flight stays disabled as defined in `dashboard-tenant-grid`.

#### Scenario: Unreachable
- **WHEN** the gateway is unreachable
- **THEN** every tier selector in the tenant grid is disabled

#### Scenario: Metrics unavailable
- **WHEN** the latest snapshot succeeded with `"metrics":null`
- **THEN** the tier selectors in the tenant grid are enabled
