## ADDED Requirements

### Requirement: Snapshot poll carries the grid query
Each snapshot request SHALL carry the tenant grid's applied filters and page as defined in `dashboard-tenant-filters`, and the dashboard SHALL record which filters and page the shown snapshot answered, so the grid can tell current results from older ones (FR-13, FR-14).

#### Scenario: Filtered poll
- **WHEN** Tier Gold is applied
- **THEN** every following snapshot request carries `tier=Gold`, `page=0` and `size=10`

#### Scenario: Shown snapshot answered older filters
- **WHEN** Tier Gold was just applied and the shown snapshot answered the request without a tier
- **THEN** the dashboard knows that the shown rows do not match the applied filters yet

### Requirement: Changed grid query restarts the snapshot poll
When the applied filters or page change, the dashboard SHALL cancel a snapshot request in flight and start a new one at once, then continue about every second from that start (FR-19, NFR-6). A request cancelled this way SHALL NOT count as a failed snapshot poll, and two snapshot requests SHALL still never be in flight together.

#### Scenario: Change while waiting
- **WHEN** a snapshot answered 300 ms ago and the operator presses "Next"
- **THEN** a snapshot request with the new page starts at once instead of 700 ms later

#### Scenario: Change while a request is in flight
- **WHEN** a snapshot request is in flight and the operator chooses Status "Over"
- **THEN** that request is cancelled, a request with `status=over` starts at once, and only one request is in flight

#### Scenario: Cancelled request is not a failure
- **WHEN** the dashboard is live with one failed poll counted and a request is cancelled by a filter change
- **THEN** no "Gateway unreachable" banner is shown because of the cancelled request
