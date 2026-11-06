# dashboard-header Specification

## Purpose

Defines what the admin dashboard header shows at all times: the gateway connection status, the quota enforcement status and the countdown to the next quota reset, so the operator sees at a glance whether the data is live, whether quotas are enforced normally and when counters restart (FR-20 "Header", S3, S7, S8).

## Requirements

### Requirement: Header shows the gateway connection status
The header SHALL show a gateway connection status derived from the snapshot polls (FR-20): "Connecting" until the first snapshot arrives and before 2 failed polls, "Connected" while snapshots arrive, and "Unreachable" while the gateway is unreachable as defined in `dashboard-states`. The status SHALL be readable as text, not only by colour (FR-22).

#### Scenario: First load
- **WHEN** the dashboard is opened and no snapshot poll has completed yet
- **THEN** the header shows the connection status "Connecting"

#### Scenario: Live
- **WHEN** a snapshot poll succeeds
- **THEN** the header shows the connection status "Connected"

#### Scenario: One failed poll
- **WHEN** the dashboard is connected and one snapshot poll fails
- **THEN** the header still shows "Connected"

#### Scenario: Gateway down
- **WHEN** two snapshot polls in a row fail
- **THEN** the header shows the connection status "Unreachable"

#### Scenario: Gateway down on first load
- **WHEN** the dashboard is opened while the gateway is down and the first two snapshot polls fail
- **THEN** the header shows "Unreachable" while the loading indicator stays in the main area

#### Scenario: Gateway returns
- **WHEN** the gateway was unreachable and a snapshot poll succeeds
- **THEN** the header shows "Connected"

### Requirement: Header shows the quota enforcement status
The header SHALL show the quota enforcement status from the latest health poll (FR-20, FR-17, D10): "OK" when Redis is `UP`, "Degraded" when Redis is `DOWN`, and "Unknown" before the first health answer and when the health poll fails. Deviation, flagged: FR-20 names only OK and degraded; "Unknown" is the existing `dashboard-polling` outcome for an unreadable health poll and is shown instead of guessing.

#### Scenario: Redis up
- **WHEN** a health poll reports Redis `UP`
- **THEN** the header shows the enforcement status "OK"

#### Scenario: Redis down
- **WHEN** a health poll reports Redis `DOWN`
- **THEN** the header shows the enforcement status "Degraded" together with the yellow degraded banner

#### Scenario: Redis returns
- **WHEN** the enforcement status is "Degraded" and a health poll reports Redis `UP`
- **THEN** the header shows "OK"

#### Scenario: Health unreadable
- **WHEN** the health poll fails with a network error, a timeout or a `502` without a health body
- **THEN** the header shows the enforcement status "Unknown"

#### Scenario: Before the first health answer
- **WHEN** the dashboard is opened and no health poll has completed yet
- **THEN** the header shows the enforcement status "Unknown"

### Requirement: Header counts down to the next reset
While snapshots arrive, the header SHALL show the time to the next period boundary as the latest snapshot's `period.secondsLeft` minus the whole seconds since it arrived, never below 0, updated every second (FR-13, FR-20). It SHALL be shown as `m:ss` below one hour and `h:mm:ss` from one hour, so the production period of 1 day reads correctly (NFR-8).

#### Scenario: Middle of a period
- **WHEN** the latest snapshot arrived just now with `period.secondsLeft` `15`
- **THEN** the header shows "Resets in 0:15"

#### Scenario: Between polls
- **WHEN** the latest snapshot with `secondsLeft` `15` arrived 1 s ago and no newer snapshot has arrived
- **THEN** the header shows "Resets in 0:14"

#### Scenario: Period reset
- **WHEN** a snapshot with `secondsLeft` `1` is followed by a snapshot of the next period with `secondsLeft` `60`
- **THEN** the countdown goes from "0:01" to "1:00"

#### Scenario: Late snapshot does not go negative
- **WHEN** the latest snapshot with `secondsLeft` `1` arrived 3 s ago and the gateway is still considered connected
- **THEN** the header shows "Resets in 0:00"

#### Scenario: Day-long period
- **WHEN** a snapshot has `secondsLeft` `3725`
- **THEN** the header shows "Resets in 1:02:05"

#### Scenario: Redis down
- **WHEN** the snapshot has `"metrics":null` and `period.secondsLeft` `42`
- **THEN** the header still shows "Resets in 0:42"

### Requirement: Countdown is not shown without live data
Before the first snapshot and while the gateway is unreachable, the header SHALL show the reset countdown as "—" instead of a time, so no stale or invented time is shown (FR-21 "Initial loading", "Gateway unreachable"). The countdown SHALL return with the first successful snapshot.

#### Scenario: First load
- **WHEN** the dashboard is opened and no snapshot has arrived
- **THEN** the header shows "Resets in —" and no digits

#### Scenario: Gateway unreachable
- **WHEN** the gateway becomes unreachable after a snapshot with `secondsLeft` `30`
- **THEN** the header shows "Resets in —"

#### Scenario: Gateway returns
- **WHEN** the gateway was unreachable and a snapshot with `secondsLeft` `12` arrives
- **THEN** the header shows "Resets in 0:12"
