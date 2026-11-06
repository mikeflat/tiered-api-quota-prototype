# Spec Delta

## Purpose

Defines what the admin dashboard's Tenants region shows — one page of tenants with usage, rejected count and status — and how an operator changes a tenant's tier from it, including what happens while a change is saving, when it succeeds, when it fails, and while metrics are unavailable or the gateway is unreachable (FR-20 "Tenant grid", FR-21, S4, S6, S7, S8).

## ADDED Requirements

### Requirement: Grid shows the snapshot's tenant page
The Tenants region SHALL show the rows of the latest shown snapshot's `tenants.rows` as a table, in the order received (sorted by name by the gateway, up to 10 rows), with the columns Tenant (name), ID, Tier, Used, Rejected and Status, and SHALL be updated with every new snapshot (FR-13, FR-20, S4).

#### Scenario: Default page
- **WHEN** a snapshot arrives with 10 rows starting with "Acme Corp" and "Blue Bank"
- **THEN** the Tenants region shows a table with 10 data rows in that order, with headers Tenant, ID, Tier, Used, Rejected and Status

#### Scenario: Usage grows
- **WHEN** the next snapshot reports tenant `1` with `used` `86` instead of `85`
- **THEN** tenant `1`'s row shows the new usage

#### Scenario: No rows
- **WHEN** a snapshot has `tenants.rows` `[]` and `total` `0`
- **THEN** the Tenants region shows "No tenants" instead of an empty table

### Requirement: Usage is shown against the limit with a percentage
Each row SHALL show its usage as `used / limit (P%)`, where P is `used × 100 / limit` rounded down, with thousands separators; P SHALL exceed 100 when the tenant overshot its limit (FR-6, FR-20).

#### Scenario: Near the limit
- **WHEN** a row has `used` `85` and `limit` `100`
- **THEN** its Used cell shows "85 / 100 (85%)"

#### Scenario: Rounded down
- **WHEN** a row has `used` `999` and `limit` `1000`
- **THEN** its Used cell shows "999 / 1,000 (99%)", not 100%

#### Scenario: Overshoot
- **WHEN** a row has `used` `103` and `limit` `100`
- **THEN** its Used cell shows "103 / 100 (103%)"

### Requirement: Rejected count and status are shown per row
Each row SHALL show its `rejected` count and its status as the text "OK", "Near" or "Over" with a matching colour, so status is readable without colour (FR-20, FR-22, S2).

#### Scenario: Over-limit tenant
- **WHEN** a row has `status` `over` and `rejected` `1240`
- **THEN** its Rejected cell shows "1,240" and its Status cell shows "Over"

#### Scenario: Near and ok
- **WHEN** rows have `status` `near` and `ok`
- **THEN** their Status cells show "Near" and "OK"

### Requirement: Grid shows how many tenants match
Below the table the grid SHALL show "Showing N of T tenants", where N is the number of rows shown and T is `tenants.total` (FR-13, FR-20).

#### Scenario: First page of the seed tenants
- **WHEN** a snapshot has 10 rows and `total` `15`
- **THEN** the grid shows "Showing 10 of 15 tenants"

### Requirement: Rows without usage while metrics are unavailable
When a row has `used`, `rejected` and `status` set to `null`, the grid SHALL still show its name, id and tier, show "—" in Used, Rejected and Status, and keep its tier selector enabled (FR-15, FR-21 "Metrics unavailable", S7).

#### Scenario: Redis down
- **WHEN** a snapshot has `"metrics":null` and rows with `"used":null`, `"rejected":null`, `"status":null`
- **THEN** each row shows its name, id and tier and "—" in Used, Rejected and Status
- **AND** the tier selectors are enabled

### Requirement: Unavailable rows are reported
When `tenants.rowsAvailable` is `false`, the Tenants region SHALL show "Tenant rows unavailable" instead of a table (FR-15).

#### Scenario: Rows unavailable
- **WHEN** a snapshot has `tenants.rowsAvailable` `false` and `rows` `null`
- **THEN** the Tenants region shows "Tenant rows unavailable" and no table

### Requirement: Tier list is loaded from the gateway
The dashboard SHALL request `GET /admin/tiers` when it starts and, if that fails or gets no answer within 2 s, again every 5 s until it succeeds (FR-16, NFR-8). Each selector SHALL offer every tier returned, in the returned order, labelled with its name and limit.

#### Scenario: Tiers loaded
- **WHEN** `GET /admin/tiers` answers `[{"name":"Bronze","limit":100},{"name":"Silver","limit":1000},{"name":"Gold","limit":10000}]`
- **THEN** each row's selector offers "Bronze (100)", "Silver (1,000)" and "Gold (10,000)", with the row's current tier selected

#### Scenario: Gateway down at start
- **WHEN** the first `GET /admin/tiers` fails and the gateway answers the next one 5 s later
- **THEN** the selectors offer the tiers from that answer
- **AND** no further `GET /admin/tiers` is sent

#### Scenario: Tiers not loaded yet
- **WHEN** rows are shown but no `GET /admin/tiers` has succeeded
- **THEN** each row's selector shows only the row's current tier and is disabled

### Requirement: Choosing a tier sends a tier change
Choosing a different tier in a row's selector SHALL send `PUT /admin/tenants/{id}/tier` with `{"tier":"<name>"}` for that row's tenant (FR-11, FR-16, S6). Choosing the tier the row already has SHALL send nothing.

#### Scenario: Upgrade
- **WHEN** the operator chooses "Gold (10,000)" in the row of Bronze tenant `1`
- **THEN** the dashboard sends `PUT /admin/tenants/1/tier` with `{"tier":"Gold"}`

#### Scenario: Same tier
- **WHEN** the operator chooses the tier the row already shows
- **THEN** no request is sent

### Requirement: Selector shows a change in progress
While a tier change is in flight, that row's selector SHALL show the requested tier, be disabled and be marked "Saving…"; other rows SHALL stay usable (FR-20).

#### Scenario: Saving
- **WHEN** the tier change for tenant `1` has been sent and not answered
- **THEN** tenant `1`'s selector shows the requested tier, is disabled and "Saving…" is shown in its row
- **AND** other rows' selectors are enabled

#### Scenario: Snapshot during saving
- **WHEN** a snapshot that still reports the old tier arrives while the change is in flight
- **THEN** the selector keeps showing the requested tier

### Requirement: Successful change shows the new tier
When the tier change answers `200`, the row SHALL show the tier and limit from the answer, without an error, until a snapshot reports that tier for the tenant, after which the snapshot's values are shown (FR-11, S6).

#### Scenario: Change confirmed
- **WHEN** `PUT /admin/tenants/1/tier` answers `200` with `{"id":1,"name":"Acme Corp","tier":"Gold","limit":10000}`
- **THEN** tenant `1`'s selector shows Gold, is enabled again and "Saving…" is removed
- **AND** its Used cell is computed against limit `10000`

#### Scenario: Older snapshot after success
- **WHEN** a snapshot that was requested before the change arrives after the `200` and still reports Bronze
- **THEN** tenant `1`'s selector still shows Gold

#### Scenario: Snapshot catches up
- **WHEN** a later snapshot reports tenant `1` as Gold
- **THEN** the row shows the snapshot's values

### Requirement: Failed change shows an inline error and reverts
When the tier change answers with a non-`2xx` status, fails with a network error, or gets no answer within 2 s, the row SHALL show an inline error starting "Tier change failed" with the reason, and the selector SHALL revert to the tier of the latest snapshot and become enabled (FR-21 "Tier change failed"). The error SHALL stay until the next change on that row.

#### Scenario: Unknown tenant
- **WHEN** `PUT /admin/tenants/1/tier` answers `404`
- **THEN** tenant `1`'s row shows "Tier change failed: tenant not found" and its selector shows the tier from the latest snapshot

#### Scenario: Server error
- **WHEN** the change answers `502` because the gateway is down behind nginx
- **THEN** the row shows "Tier change failed: gateway error (502)" and the selector reverts

#### Scenario: Timeout
- **WHEN** the change gets no answer within 2 s
- **THEN** the request is cancelled, the row shows "Tier change failed: no answer within 2 s" and the selector reverts

#### Scenario: Network error
- **WHEN** the change fails before any response arrives
- **THEN** the row shows "Tier change failed: gateway unreachable" and the selector reverts

#### Scenario: Retry clears the error
- **WHEN** a row shows a tier change error and the operator chooses a tier again
- **THEN** the error is removed and the new change is sent

### Requirement: Selectors follow the connection state
Tier selectors SHALL be disabled before the first snapshot and while the gateway is unreachable, and enabled while live, including while metrics are unavailable or enforcement is degraded (FR-21, D6). While unreachable the grid SHALL keep the last known rows, greyed out with the other data regions.

#### Scenario: Gateway down
- **WHEN** the gateway becomes unreachable
- **THEN** the grid keeps the last rows greyed out and every tier selector is disabled

#### Scenario: Redis down
- **WHEN** health reports Redis `DOWN` and the snapshot has `"metrics":null`
- **THEN** every tier selector is enabled and a tier change can be sent

#### Scenario: Gateway returns
- **WHEN** the gateway was unreachable and a snapshot arrives
- **THEN** the tier selectors are enabled again
