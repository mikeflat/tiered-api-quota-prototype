## MODIFIED Requirements

### Requirement: Unavailable rows are reported
When `tenants.rowsAvailable` is `false`, the Tenants region SHALL show "Tenant rows unavailable" instead of a table (FR-15). Because the gateway only reports rows unavailable for a status filter it can't answer without Redis, the message SHALL read "Tenant rows unavailable: the status filter needs metrics" and SHALL be followed by a "Clear status filter" button, as defined in `dashboard-tenant-filters`.

#### Scenario: Rows unavailable
- **WHEN** a snapshot has `tenants.rowsAvailable` `false` and `rows` `null`
- **THEN** the Tenants region shows "Tenant rows unavailable: the status filter needs metrics" and no table
- **AND** a "Clear status filter" button is shown
