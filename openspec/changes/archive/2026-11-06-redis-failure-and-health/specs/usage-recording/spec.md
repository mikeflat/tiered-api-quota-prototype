## ADDED Requirements

### Requirement: Recording outage start and end are logged once
When a Redis call of the writer fails after the previous call succeeded (or as the first call), the gateway SHALL log one warning that recording is failing, no new tenants get blocked and counting is paused, with the failure cause (FR-8, D6). Further failures SHALL NOT log again. The first success after failures SHALL log one info line that recording resumed.

#### Scenario: Redis becomes unreachable
- **WHEN** events were recorded successfully, Redis becomes unreachable, and tenants keep sending requests so that `20` events fail
- **THEN** exactly one warning about the outage is logged for those `20` failures
- **AND** the warning states that no new tenants are blocked and counting is paused, and names the failure cause

#### Scenario: Redis unreachable from the start
- **WHEN** the gateway starts without Redis and the first event fails
- **THEN** one outage warning is logged

#### Scenario: Redis returns
- **WHEN** events failed during an outage and Redis then becomes reachable and the next event is recorded
- **THEN** one info line is logged stating that recording resumed
- **AND** a later failure logs a new outage warning

#### Scenario: No traffic, no log
- **WHEN** Redis is unreachable but no business request arrives
- **THEN** no outage warning is logged

### Requirement: Events lost during an outage are not tracked
While Redis is unreachable, events SHALL simply not be counted, and enforcement SHALL rely only on the current blocked set (FR-8). The gateway SHALL NOT report or log how many events were lost, neither periodically nor in the outage log lines. Deviation from FR-6b and backlog item 6, which mention logged drops: drops are not logged, by decision for this change.

#### Scenario: Outage with traffic
- **WHEN** Redis is unreachable for a while and tenants send requests during that time
- **THEN** the log holds only the one outage warning and, after Redis returns, the one resume line
- **AND** neither line states a number of lost, failed or dropped events

## MODIFIED Requirements

### Requirement: Redis failures do not stop recording
When a Redis call fails or times out, the writer SHALL discard that event, increment an in-memory failed-events count and continue with the next event; it SHALL NOT retry the event or terminate (FR-8, D6). When Redis becomes reachable again, later events SHALL be counted without a gateway restart. Each Redis call SHALL time out after a configurable short duration (default 1 s). Logging of the outage is described in "Recording outage start and end are logged once".

#### Scenario: Redis unavailable
- **WHEN** Redis is unreachable and a known tenant sends requests
- **THEN** every response is `200`, the failed-events count grows, and nothing is counted in Redis for those requests

#### Scenario: Redis returns
- **WHEN** Redis was unreachable, becomes reachable again, and tenant `1` then sends a request in period `p`
- **THEN** that request is counted in `quota:used:1:{p}` without restarting the gateway

#### Scenario: Redis stops mid-run
- **WHEN** tenants are sending requests that are being counted and Redis stops answering
- **THEN** responses keep their status and do not wait for Redis, and the writer counts each event whose Redis call failed or timed out as failed
- **AND** if Redis was unreachable, those events are not counted in Redis; if Redis hung, record calls that timed out may still be applied by Redis once it answers again (the gateway does not retry or cancel them)
