# dashboard-polling Specification

## Purpose

Defines how the admin dashboard fetches live data from the gateway: the snapshot and health polling cadence, per-poll timeouts, non-overlapping polls and what counts as a failed poll, so every panel works from the same fresh data (FR-19, NFR-6).

## Requirements

### Requirement: Snapshot is polled every second
While the dashboard is open it SHALL request `GET /admin/snapshot` from its own origin as soon as it starts and then about once per second (FR-19, D9). While no request is in flight, the next one SHALL start 1 s after the previous one started, or immediately after it finished when that took longer.

#### Scenario: First poll on open
- **WHEN** the dashboard is opened
- **THEN** it sends `GET /admin/snapshot` without waiting for the first interval

#### Scenario: Steady cadence
- **WHEN** every snapshot request completes within 200 ms
- **THEN** a new snapshot request starts about every 1 s

#### Scenario: Polling continues after failures
- **WHEN** snapshot requests keep failing because the gateway is down
- **THEN** the dashboard keeps sending a snapshot request about every 1 s

### Requirement: Health is polled every five seconds
While the dashboard is open it SHALL request `GET /actuator/health` from its own origin as soon as it starts and then about every 5 s, independently of the snapshot poll (FR-19, D10).

#### Scenario: Health cadence
- **WHEN** the dashboard has been open for 11 s and health requests complete quickly
- **THEN** it has sent 3 health requests, about 5 s apart

#### Scenario: Health is independent of the snapshot
- **WHEN** a snapshot request is slow or failing
- **THEN** health requests still start on their own schedule

### Requirement: Polls of one kind never overlap
The dashboard SHALL have at most one snapshot request and at most one health request in flight at any time (FR-19). A slow request delays the next poll of its kind instead of running alongside it.

#### Scenario: Slow snapshot
- **WHEN** a snapshot request takes 1.5 s to answer
- **THEN** no second snapshot request is started before it completes

### Requirement: Each poll times out after two seconds
A snapshot or health request that has not completed 2 s after it started SHALL be abandoned and treated as failed (FR-21), and the next poll of its kind SHALL follow without waiting for the abandoned request.

#### Scenario: Gateway hangs
- **WHEN** the gateway accepts a snapshot request but does not answer
- **THEN** after 2 s the request is cancelled, counted as a failed snapshot poll, and the next snapshot request starts

### Requirement: Failed snapshot polls are classified
A snapshot poll SHALL count as failed on a network error, a 2 s timeout, a `5xx` status, any other non-`2xx` status, or a `2xx` body that is not a valid snapshot (missing `period` or `tenants`). Every other answer SHALL count as successful, including one with `"metrics":null`. Deviation, flagged: FR-21 lists network error, timeout and `5xx`; a `4xx` or malformed body can only come from a dashboard or proxy fault and is also treated as failed, so no wrong data is shown.

#### Scenario: Gateway stopped behind nginx
- **WHEN** the gateway is stopped and the dashboard origin answers the snapshot request with `502`
- **THEN** the poll counts as failed

#### Scenario: Network error
- **WHEN** the snapshot request fails before any response arrives
- **THEN** the poll counts as failed

#### Scenario: Redis down
- **WHEN** the snapshot answers `200` with `"metrics":null`
- **THEN** the poll counts as successful

#### Scenario: Malformed body
- **WHEN** the snapshot answers `200` with a body that is not JSON or has no `period`
- **THEN** the poll counts as failed

### Requirement: Health poll yields the enforcement status
Each health poll SHALL yield one enforcement status: `degraded` when the body's `components.redis.status` is `DOWN`, whether the HTTP status is `200` or `503`; `ok` when it is `UP`; `unknown` on a network error, timeout, unreadable body or a body without a Redis component (FR-17, D10).

#### Scenario: Redis up
- **WHEN** health answers `200` with `{"status":"UP","components":{"redis":{"status":"UP"}}}`
- **THEN** the enforcement status is `ok`

#### Scenario: Redis down
- **WHEN** health answers `503` with `{"status":"DOWN","components":{"redis":{"status":"DOWN"}}}`
- **THEN** the enforcement status is `degraded`

#### Scenario: Gateway down
- **WHEN** the health request fails with a network error, a timeout or a `502` without a health body
- **THEN** the enforcement status is `unknown`

### Requirement: Polling stops when the dashboard closes
When the dashboard app is unmounted it SHALL stop scheduling polls and cancel requests in flight, so no state update happens afterwards.

#### Scenario: Unmount during a request
- **WHEN** the app is unmounted while a snapshot request is in flight
- **THEN** that request is cancelled and no further snapshot or health request is sent
