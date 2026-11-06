# Spec Delta

## Purpose

Defines what the admin dashboard shows while data is loading, while the gateway is unreachable and after it recovers, while quota enforcement is degraded and while metrics are unavailable, so the operator never mistakes stale or missing data for live data (FR-21, S7–S9).

## ADDED Requirements

### Requirement: Loading indicator until the first snapshot
Until the first successful snapshot poll, the dashboard SHALL show a loading indicator in place of the data regions and SHALL NOT show any traffic, totals or tenant numbers (FR-21 "Initial loading", S9).

#### Scenario: First load
- **WHEN** the dashboard is opened and the first snapshot has not arrived yet
- **THEN** a loading indicator is shown
- **AND** no Traffic, Period totals or Tenants region is shown and no digits appear in the main area

#### Scenario: First snapshot arrives
- **WHEN** the first snapshot poll succeeds
- **THEN** the loading indicator is removed and the Traffic, Period totals and Tenants regions are shown

### Requirement: Gateway unreachable after two failed snapshot polls
After 2 consecutive failed snapshot polls the dashboard SHALL show a red "Gateway unreachable" banner and keep polling (FR-21, S8). A single failed poll SHALL change nothing on screen.

#### Scenario: One failed poll
- **WHEN** the dashboard shows live data and one snapshot poll fails, followed by a successful one
- **THEN** no "Gateway unreachable" banner is shown at any time

#### Scenario: Gateway stops
- **WHEN** the dashboard shows live data and two snapshot polls in a row fail
- **THEN** a "Gateway unreachable" banner is shown

#### Scenario: Gateway down on first load
- **WHEN** the dashboard is opened while the gateway is down and the first two snapshot polls fail
- **THEN** the "Gateway unreachable" banner is shown together with the loading indicator, and no numbers are shown

### Requirement: Last known data is greyed out while unreachable
While the gateway is unreachable, the dashboard SHALL keep showing the last successfully received data greyed out, with the note "last updated N s ago", where N is the whole seconds since that snapshot arrived and is updated every second (FR-21).

#### Scenario: Stale data
- **WHEN** the last successful snapshot arrived 7 s ago and the gateway is unreachable
- **THEN** the data regions are visibly greyed out and the note reads "last updated 7 s ago"

#### Scenario: Note keeps counting
- **WHEN** the gateway stays unreachable for 3 more seconds
- **THEN** the note reads "last updated 10 s ago" and the data shown has not changed

### Requirement: Tier controls are disabled while unreachable
While the gateway is unreachable or no snapshot has arrived yet, tier change controls SHALL be disabled; in every other state, including metrics unavailable and enforcement degraded, they SHALL be enabled (FR-21). The controls themselves arrive with the tenant grid; this change provides the enabled flag they read.

#### Scenario: Unreachable
- **WHEN** the gateway is unreachable
- **THEN** the dashboard state marks tier controls as disabled

#### Scenario: Metrics unavailable
- **WHEN** the latest snapshot succeeded with `"metrics":null`
- **THEN** the dashboard state marks tier controls as enabled

### Requirement: Recovery shows a brief Reconnected notice
The first successful snapshot poll after the gateway was unreachable SHALL remove the "Gateway unreachable" banner and the greying, show the received data, enable tier controls, and show a "Reconnected" notice for about 3 s (FR-21 "Recovered", S8).

#### Scenario: Gateway returns
- **WHEN** the gateway was unreachable and a snapshot poll succeeds
- **THEN** the banner and the "last updated" note are removed, the data regions show the new snapshot without greying, and a "Reconnected" notice is shown

#### Scenario: Notice disappears
- **WHEN** 3 s have passed since the "Reconnected" notice appeared
- **THEN** the notice is no longer shown

#### Scenario: No notice without an outage
- **WHEN** a single snapshot poll fails and the next one succeeds
- **THEN** no "Reconnected" notice is shown

### Requirement: Degraded enforcement banner
While the latest health poll reports enforcement `degraded`, the dashboard SHALL show a yellow banner "Quota enforcement degraded: no new blocks, counting paused" (FR-21, S7). The banner SHALL be removed when a health poll reports `ok` or `unknown`.

#### Scenario: Redis stops
- **WHEN** a health poll reports Redis `DOWN`
- **THEN** the yellow degraded banner is shown

#### Scenario: Redis returns
- **WHEN** the degraded banner is shown and a health poll reports Redis `UP`
- **THEN** the banner is removed

#### Scenario: Gateway down
- **WHEN** the degraded banner is shown and the health poll fails because the gateway is down
- **THEN** the degraded banner is removed, and only the "Gateway unreachable" banner remains once the snapshot polls have failed twice

### Requirement: Metrics unavailable marker
When the latest successful snapshot has `"metrics":null`, the Traffic and Period totals regions SHALL each show "Metrics unavailable" instead of numbers, while the Tenants region stays shown (FR-15, FR-21, S7). The marker SHALL disappear with the first snapshot that has metrics again.

#### Scenario: Redis down
- **WHEN** a snapshot poll succeeds with `"metrics":null`
- **THEN** the Traffic and Period totals regions each show "Metrics unavailable" and no traffic or totals numbers
- **AND** the Tenants region is still shown

#### Scenario: Metrics return
- **WHEN** the next successful snapshot has non-null `metrics`
- **THEN** "Metrics unavailable" is no longer shown

### Requirement: States combine without hiding each other
The unreachable, reconnected, degraded and metrics-unavailable indications SHALL be independent: any combination that the polls produce SHALL be shown together, with banners above the data regions (FR-21).

#### Scenario: Redis down and metrics unavailable
- **WHEN** health reports Redis `DOWN` and the snapshot has `"metrics":null`
- **THEN** the degraded banner and the "Metrics unavailable" markers are shown together

#### Scenario: Reconnected while Redis is down
- **WHEN** the gateway returns after an outage while Redis is still down
- **THEN** the "Reconnected" notice and "Metrics unavailable" are shown, and the degraded banner appears with the next health poll that reports Redis `DOWN`
