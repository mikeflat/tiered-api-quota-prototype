# Spec Delta

## Purpose

Defines what the admin dashboard's Traffic and Period totals regions show — current allowed and rejected req/s, the 60-second trend, period totals and the number of tenants near and over their limit — and how they behave when metrics are missing or stale (FR-20, S1–S4, S7, S8).

## ADDED Requirements

### Requirement: Traffic shows the last complete second
The Traffic region SHALL show the allowed and the rejected requests per second of the last complete second, taken from the newest entry of the latest snapshot's `metrics.perSecond`, each labelled in text ("Allowed", "Rejected") with the unit "req/s" (FR-20 "Traffic", S1, S2, S4).

#### Scenario: Live traffic
- **WHEN** the newest `perSecond` entry has `allowed` `183` and `rejected` `12`
- **THEN** the Traffic region shows "Allowed 183 req/s" and "Rejected 12 req/s"

#### Scenario: Quiet second
- **WHEN** the newest `perSecond` entry has `allowed` `0` and `rejected` `0`
- **THEN** the Traffic region shows "Allowed 0 req/s" and "Rejected 0 req/s"

#### Scenario: Next poll
- **WHEN** a newer snapshot arrives whose newest entry has `allowed` `190`
- **THEN** the Traffic region shows "Allowed 190 req/s"

#### Scenario: No per-second entries
- **WHEN** a snapshot has metrics with an empty `perSecond` list
- **THEN** the Traffic region shows "—" for allowed and rejected req/s instead of a number

### Requirement: Traffic shows a 60-second trend
The Traffic region SHALL draw the allowed and rejected req/s of every `perSecond` entry, oldest left and newest right, as two lines on one vertical scale starting at 0 and reaching the highest allowed or rejected value of those entries. It SHALL be drawn as inline SVG without a chart library (FR-20, FR-22, architecture §2.3).

#### Scenario: Full minute
- **WHEN** the snapshot has 60 `perSecond` entries
- **THEN** the trend shows one allowed line and one rejected line, each with 60 points from left to right

#### Scenario: Shared scale
- **WHEN** the highest allowed value is `200` and the highest rejected value is `50`
- **THEN** an allowed point of `200` is drawn at the top of the trend and a rejected point of `50` at a quarter of its height

#### Scenario: No traffic at all
- **WHEN** every `perSecond` entry has `allowed` `0` and `rejected` `0`
- **THEN** both lines are drawn flat along the bottom of the trend and nothing fails

#### Scenario: Trend moves with polls
- **WHEN** a newer snapshot arrives whose `perSecond` is shifted by one second
- **THEN** the trend is redrawn from the new entries

### Requirement: Trend is identifiable without colour
The trend SHALL have a text legend naming the allowed and rejected lines, and a text alternative stating that it covers the last 60 seconds and the peak allowed and rejected req/s in it (FR-22).

#### Scenario: Text alternative
- **WHEN** the trend shows entries with a peak of `200` allowed and `50` rejected req/s
- **THEN** the trend is exposed as an image whose accessible name mentions the last 60 seconds, "200" allowed and "50" rejected

#### Scenario: Legend
- **WHEN** the trend is shown
- **THEN** a legend with the texts "Allowed" and "Rejected" is shown next to it

### Requirement: Period totals show allowed and rejected requests
The Period totals region SHALL show the allowed and the rejected request totals of the current period from `metrics.totals`, with a thousands separator for large numbers (FR-20 "Period totals").

#### Scenario: Totals
- **WHEN** `metrics.totals` is `{"allowed":12045,"rejected":731}`
- **THEN** the Period totals region shows "Allowed 12,045" and "Rejected 731"

#### Scenario: New period
- **WHEN** a snapshot of a new period has `metrics.totals` `{"allowed":0,"rejected":0}`
- **THEN** the Period totals region shows "Allowed 0" and "Rejected 0"

### Requirement: Period totals show near and over tenant counts
The Period totals region SHALL show the number of tenants near their limit from `metrics.tenantsNear` and over their limit from `metrics.tenantsOver`, labelled "Near limit" and "Over limit" (FR-20, S2, S3).

#### Scenario: Tenants near and over
- **WHEN** `metrics.tenantsNear` is `2` and `metrics.tenantsOver` is `1`
- **THEN** the Period totals region shows "Near limit 2" and "Over limit 1"

#### Scenario: Period reset
- **WHEN** a snapshot of a new period has `tenantsNear` `0` and `tenantsOver` `0`
- **THEN** the Period totals region shows "Near limit 0" and "Over limit 0"

### Requirement: Panels show no numbers while metrics are unavailable
When the latest shown snapshot has `"metrics":null`, the Traffic and Period totals regions SHALL show only "Metrics unavailable", with no req/s, trend, totals or near/over counts, as required by `dashboard-states` (FR-15, FR-21, S7). The panels SHALL return with the first snapshot that has metrics.

#### Scenario: Redis down
- **WHEN** a snapshot poll succeeds with `"metrics":null`
- **THEN** the Traffic and Period totals regions show "Metrics unavailable", no digits and no trend

#### Scenario: Redis returns
- **WHEN** metrics were unavailable and a snapshot with metrics arrives
- **THEN** the Traffic region shows req/s and the trend, and the Period totals region shows totals and near/over counts

### Requirement: Panels keep last known numbers while unreachable
While the gateway is unreachable, the Traffic and Period totals regions SHALL keep showing the values of the last successful snapshot, greyed out with the rest of the data regions, and SHALL NOT change until a snapshot arrives again (FR-21, S8).

#### Scenario: Gateway stops
- **WHEN** the last snapshot showed "Allowed 183 req/s" and totals "Allowed 12,045", and the gateway becomes unreachable
- **THEN** the same values and the same trend are still shown, greyed out

#### Scenario: Gateway returns
- **WHEN** the gateway was unreachable and a snapshot with newest entry `allowed` `95` arrives
- **THEN** the Traffic region shows "Allowed 95 req/s" without greying

#### Scenario: Gateway down on first load
- **WHEN** the gateway is down when the dashboard opens and no snapshot has arrived
- **THEN** no traffic or totals panel is shown, only the loading indicator
