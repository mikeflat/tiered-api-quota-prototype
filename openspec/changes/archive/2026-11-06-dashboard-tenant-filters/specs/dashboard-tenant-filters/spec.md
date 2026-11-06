# Spec Delta

## Purpose

Defines the filter controls and page navigation of the admin dashboard's tenant grid — name, id, tier and status filters, pages of 10 — how they are validated, applied to the snapshot poll and kept across polls, and how they behave while metrics are unavailable or the gateway is unreachable (FR-14, FR-15, FR-20, FR-21, S5, S7).

## ADDED Requirements

### Requirement: Grid has name, id, tier and status filters
Above the tenant grid the dashboard SHALL show the filters Name (text, contains), ID (whole number, exact), Tier ("All tiers" plus every tier from the loaded tier list) and Status ("All statuses", "OK", "Near", "Over"), each with a visible label, and a "Clear filters" button (FR-14, FR-20, S5). Set filters SHALL combine.

#### Scenario: Controls
- **WHEN** the dashboard shows the tenant grid and the tier list has loaded
- **THEN** it shows the labelled controls Name, ID, Tier and Status and a "Clear filters" button
- **AND** the Tier control offers "All tiers", "Bronze", "Silver" and "Gold"

#### Scenario: Tier list not loaded
- **WHEN** no `GET /admin/tiers` has succeeded yet
- **THEN** the Tier control offers only "All tiers"

#### Scenario: Combined filters
- **WHEN** the operator sets Tier to Bronze and types "co" in Name
- **THEN** the grid shows only the rows the gateway returns for both filters, e.g. "Acme Corp" and "Cobalt Labs"

#### Scenario: Clear filters
- **WHEN** filters are set and the operator presses "Clear filters"
- **THEN** every filter returns to empty / "All" and the grid shows the first page of all tenants

### Requirement: Filters and page are sent with the snapshot poll
Every snapshot poll SHALL carry the applied filters and page as query parameters: `name` and `id` when set, `tier` and `status` when not "All", always `page` (from 0) and `size` `10` (FR-13, FR-14). Values SHALL be URL-encoded.

#### Scenario: No filters
- **WHEN** no filter is set and the first page is shown
- **THEN** the snapshot is requested as `GET /admin/snapshot?page=0&size=10`

#### Scenario: All filters
- **WHEN** Name is "co & sons", ID is `3`, Tier is Bronze, Status is Over and the second page is shown
- **THEN** the request carries `name=co+%26+sons` (or `%20` for spaces), `id=3`, `tier=Bronze`, `status=over`, `page=1` and `size=10`

### Requirement: Typed filters are applied after a pause
Text typed in Name or ID SHALL be applied 300 ms after the last keystroke; choosing a Tier or Status, clearing filters, and changing the page SHALL be applied at once (S5).

#### Scenario: Typing a name
- **WHEN** the operator types "bank" one letter at a time, less than 300 ms apart
- **THEN** no snapshot is requested with a partial name
- **AND** 300 ms after the last letter the snapshot is requested with `name=bank`

#### Scenario: Choosing a status
- **WHEN** the operator chooses Status "Over"
- **THEN** the snapshot is requested with `status=over` without waiting

### Requirement: Invalid ID is not applied
When the ID text is not empty and not a whole number from 1, the dashboard SHALL show "ID must be a whole number from 1" next to the field and SHALL keep the previously applied filters, so no snapshot request is sent that the gateway rejects with `400` (FR-14). An empty ID SHALL mean no id filter.

#### Scenario: Letters in ID
- **WHEN** the operator types "acme" in ID
- **THEN** "ID must be a whole number from 1" is shown and no snapshot request carries `id=acme`
- **AND** the grid keeps showing the rows of the previously applied filters

#### Scenario: Zero
- **WHEN** the operator types "0" in ID
- **THEN** the same message is shown and no request carries `id=0`

#### Scenario: Valid ID
- **WHEN** the operator types "1"
- **THEN** the message disappears and the snapshot is requested with `id=1`

#### Scenario: ID that matches no tenant
- **WHEN** the snapshot for `id=99` returns no rows and `total` `0`
- **THEN** the grid shows "No tenants"

### Requirement: Grid has page navigation
Below the grid the dashboard SHALL show "Page X of Y", where X is the shown page + 1 and Y is `ceil(total / 10)` but at least 1, with "Previous" and "Next" buttons; "Previous" SHALL be disabled on the first page and "Next" on the last (FR-14, FR-20, S5).

#### Scenario: Seed tenants
- **WHEN** no filter is set and `total` is `15`
- **THEN** the grid shows "Page 1 of 2", "Previous" is disabled and "Next" is enabled

#### Scenario: Next page
- **WHEN** the operator presses "Next" on page 1 of 2
- **THEN** the snapshot is requested with `page=1` and, once it answers, the grid shows the remaining 5 tenants, "Page 2 of 2" and "Showing 5 of 15 tenants", with "Next" disabled

#### Scenario: No matches
- **WHEN** the filters match no tenant
- **THEN** the grid shows "No tenants" and "Page 1 of 1" with both buttons disabled

### Requirement: A filter change returns to the first page
Changing any filter or clearing the filters SHALL request page 0 (S5).

#### Scenario: Filter on page 2
- **WHEN** the grid shows page 2 and the operator chooses Tier Silver
- **THEN** the snapshot is requested with `tier=Silver` and `page=0`

### Requirement: Filters and page are kept across polls
The applied filters and page SHALL stay as they are across snapshot polls, tier changes, reconnects and metrics outages until the operator changes them (FR-20, S5).

#### Scenario: Live data while filtered
- **WHEN** Status is "Over", the grid shows page 1, and several further snapshots arrive
- **THEN** every snapshot request carries `status=over` and `page=0`, the controls keep their values and the rows refresh with each snapshot

#### Scenario: After a reconnect
- **WHEN** the gateway was unreachable while Tier was Gold and it returns
- **THEN** the next snapshot request still carries `tier=Gold` and the Tier control still shows Gold

### Requirement: Page follows a shrinking result
When a snapshot for the applied filters reports a page past the last page, the dashboard SHALL switch to the last page, which is the first page when `total` is 0 (S3, S5).

#### Scenario: Tenants leave the over set at a reset
- **WHEN** Status is "Over", the grid shows page 2, and after a period reset the snapshot reports `total` `4` with no rows
- **THEN** the snapshot is requested again with `page=0` and the grid shows "Page 1 of 1"

#### Scenario: Nothing matches any more
- **WHEN** the shown page is 2 and the snapshot reports `total` `0`
- **THEN** the snapshot is requested again with `page=0` and the grid shows "No tenants" and "Page 1 of 1"

### Requirement: Results for new filters are not confused with old ones
After filters or page change, until a snapshot answers for the newly applied filters and page, the grid SHALL keep the previous rows greyed out and marked "Updating…" (NFR-6).

#### Scenario: Waiting for the new page
- **WHEN** the operator presses "Next" and the snapshot for `page=1` has not answered
- **THEN** the previous rows are shown greyed out with "Updating…"

#### Scenario: Answer arrives
- **WHEN** the snapshot for `page=1` answers
- **THEN** "Updating…" disappears and its rows are shown

### Requirement: Status filter needs metrics
While the latest snapshot has `"metrics":null`, the Status filter SHALL be disabled; the Name, ID and Tier filters and page navigation SHALL stay enabled (FR-15, FR-21 "Metrics unavailable", S7). A status filter already applied SHALL stay applied and shown, and can be removed with "Clear status filter" in the grid or with "Clear filters".

#### Scenario: Redis down without a status filter
- **WHEN** a snapshot arrives with `"metrics":null` and no status filter is applied
- **THEN** the Status control is disabled, the other filters and the pager stay enabled, and the grid shows rows without usage

#### Scenario: Redis down with a status filter
- **WHEN** Status is "Over" and a snapshot arrives with `"metrics":null` and `"rowsAvailable":false`
- **THEN** the Status control is disabled and still shows "Over"
- **AND** the grid shows "Tenant rows unavailable: the status filter needs metrics" with a "Clear status filter" button

#### Scenario: Clearing the status filter
- **WHEN** the operator presses "Clear status filter"
- **THEN** the snapshot is requested without `status` and the grid shows rows again

#### Scenario: Redis returns
- **WHEN** a snapshot with metrics arrives again
- **THEN** the Status control is enabled

### Requirement: Filters follow the connection state
Filter controls, "Clear filters" and page navigation SHALL be disabled while the gateway is unreachable, keeping their values, and enabled again with the first successful snapshot (FR-21, S8).

#### Scenario: Gateway down
- **WHEN** the gateway becomes unreachable while Name is "bank"
- **THEN** every filter control and both page buttons are disabled and Name still shows "bank"

#### Scenario: Gateway returns
- **WHEN** a snapshot succeeds after the outage
- **THEN** the filter controls and page buttons are enabled again
