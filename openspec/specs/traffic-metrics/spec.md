# traffic-metrics Specification

## Purpose

Defines the global traffic metrics and the per-period near-limit and over-limit tenant sets that the quota gateway maintains in Redis while recording usage, so the admin snapshot and the status filter can read them at a cost independent of the number of tenants.

## Requirements

### Requirement: Global per-second counts are recorded
Applying an allowed event SHALL increment `traffic:allowed:{s}` and applying a rejected event SHALL increment `traffic:rejected:{s}`, where `s` is the event's own epoch second, in the same atomic call as the tenant's counter (FR-12). Counts from all tenants add up in the same key.

#### Scenario: Allowed events of several tenants in one second
- **WHEN** tenant `1` sends two requests and tenant `2` sends one request, all arriving in epoch second `s`, and the writer has processed their events
- **THEN** `traffic:allowed:{s}` is `3` and `traffic:rejected:{s}` does not exist

#### Scenario: Rejected event
- **WHEN** blocked tenant `1` sends a request arriving in epoch second `s` and the writer has processed its event
- **THEN** `traffic:rejected:{s}` is `1` and `traffic:allowed:{s}` is not incremented by it

#### Scenario: Event processed in a later second
- **WHEN** an allowed event of epoch second `s` is applied after second `s + 1` has started
- **THEN** `traffic:allowed:{s}` is incremented and `traffic:allowed:{s+1}` is not

### Requirement: Per-second keys expire after 120 seconds
Each per-second key SHALL get a time-to-live of 120 seconds when the script creates it, independent of the period length, and later increments SHALL NOT extend it (architecture §4).

#### Scenario: TTL on a new per-second key
- **WHEN** the first allowed event of epoch second `s` is applied
- **THEN** `traffic:allowed:{s}` has a TTL greater than `0` and at most `120` seconds

#### Scenario: TTL does not depend on the period
- **WHEN** the period is `600` s and the first rejected event of epoch second `s` is applied
- **THEN** `traffic:rejected:{s}` has a TTL of at most `120` seconds

### Requirement: Global period totals are recorded
Applying an allowed event SHALL increment `traffic:total:allowed:{p}` and applying a rejected event SHALL increment `traffic:total:rejected:{p}`, where `p` is the event's own period index, in the same atomic call (FR-12). A new period starts from zero because its keys are new (D4).

#### Scenario: Totals across tenants
- **WHEN** in period `p` tenant `1` has three allowed and one rejected event and tenant `2` has two allowed events, all processed
- **THEN** `traffic:total:allowed:{p}` is `5` and `traffic:total:rejected:{p}` is `1`

#### Scenario: Late event counts toward its own period
- **WHEN** an allowed event of period `p` is applied after period `p + 1` has started
- **THEN** `traffic:total:allowed:{p}` is incremented and `traffic:total:allowed:{p+1}` is not

#### Scenario: New period starts from zero
- **WHEN** period `p` has `traffic:total:allowed:{p}` `40` and the first allowed event of period `p + 1` is applied
- **THEN** `traffic:total:allowed:{p+1}` is `1`

### Requirement: Tenant status is maintained in the near and over sets
After counting an event of period `p`, allowed or rejected, the script SHALL compute the tenant's status from its `used` in `p`: over when `used ≥ limit`, near when `nearBoundary ≤ used < limit`, ok otherwise. Over SHALL leave the tenant only in `quota:over:{p}`, near only in `quota:near:{p}`, ok in neither (FR-12, FR-14, D8).

#### Scenario: Tenant becomes near
- **WHEN** Bronze limit is `10`, near threshold `80` %, and an allowed event brings Bronze tenant `1` to `used` `8` in period `p`
- **THEN** tenant `1` is a member of `quota:near:{p}` and not of `quota:over:{p}`

#### Scenario: Tenant just below the near boundary
- **WHEN** Bronze limit is `10`, near threshold `80` %, and an allowed event brings Bronze tenant `1` to `used` `7` in period `p`
- **THEN** tenant `1` is in neither `quota:near:{p}` nor `quota:over:{p}`

#### Scenario: Tenant moves from near to over
- **WHEN** Bronze limit is `10`, tenant `1` is in `quota:near:{p}`, and an allowed event brings it to `used` `10`
- **THEN** tenant `1` is a member of `quota:over:{p}` and no longer of `quota:near:{p}`

#### Scenario: Overshoot stays over
- **WHEN** Bronze limit is `10` and in-flight allowed events bring tenant `1` to `used` `12`
- **THEN** tenant `1` is a member of `quota:over:{p}` only

#### Scenario: Rejected event keeps the status
- **WHEN** Bronze limit is `10`, tenant `1` has `used` `10` in period `p`, and a rejected event of tenant `1` in `p` is applied
- **THEN** tenant `1` is still a member of `quota:over:{p}` only and its `used` is still `10`

#### Scenario: Rejected event for a tenant without usage
- **WHEN** a rejected event of tenant `2` in period `p` is applied and tenant `2` has no `used` in `p`
- **THEN** tenant `2` is in neither set and `quota:used:2:{p}` is still absent

#### Scenario: Sets belong to the event's period
- **WHEN** an allowed event of period `p` is applied after period `p + 1` has started and brings the tenant to its limit in `p`
- **THEN** the tenant is a member of `quota:over:{p}` and not of `quota:over:{p+1}`

### Requirement: Status is evaluated against the tenant's current limit
The limit and near-limit boundary used for an event SHALL be those of the tenant's tier at the moment the writer applies the event, the same values used to decide blocking (FR-6, FR-11). Membership SHALL change only when an event of the tenant is applied (docs/02 §7).

#### Scenario: Status follows the tier at write time
- **WHEN** Bronze limit is `10`, Silver limit is `100`, tenant `1` has `used` `10` in period `p` and is in `quota:over:{p}`, its tier becomes Silver, and its next allowed event is applied
- **THEN** its `used` is `11`, it is in neither `quota:near:{p}` nor `quota:over:{p}`, and it is not blocked

#### Scenario: No event, no change
- **WHEN** tenant `1` is in `quota:over:{p}` and its tier changes but it sends no further request in `p`
- **THEN** tenant `1` stays in `quota:over:{p}` until the keys of `p` expire

#### Scenario: Same limit for status and blocking
- **WHEN** Bronze limit is `5` and an allowed event brings Bronze tenant `1` to `used` `5` in the current period `p`
- **THEN** tenant `1` is in `quota:over:{p}` and in the blocked set

### Requirement: Period keys of the metrics expire after two periods
Each period total and each near/over set SHALL get a time-to-live of two periods (`2 × periodSeconds`) when it is created, and later writes SHALL NOT extend it, so old periods disappear without cleanup jobs (D4, architecture §4).

#### Scenario: TTL on a new period total
- **WHEN** the first allowed event of period `p` is applied with a 60 s period
- **THEN** `traffic:total:allowed:{p}` has a TTL greater than `0` and at most `120` seconds

#### Scenario: TTL on a new set
- **WHEN** an allowed event first adds a tenant to `quota:over:{p}` with a 60 s period
- **THEN** `quota:over:{p}` has a TTL greater than `0` and at most `120` seconds

#### Scenario: Later members keep the original TTL
- **WHEN** a second tenant is added to `quota:near:{p}` some time after the first
- **THEN** the TTL of `quota:near:{p}` is not reset to the full two periods

#### Scenario: Set recreated after it emptied
- **WHEN** the only member leaves `quota:near:{p}` so the set disappears, and later another tenant becomes near in `p`
- **THEN** the recreated `quota:near:{p}` has a TTL greater than `0` and at most two periods

### Requirement: Metrics are not recorded while Redis is unavailable
When the record call fails or times out, neither the global counts nor the near/over sets SHALL be changed for that event, and the gateway SHALL NOT repair them later (FR-8, D6). The request path and enforcement SHALL behave as for any recording failure.

#### Scenario: Redis unreachable
- **WHEN** Redis is unreachable and known tenants send requests
- **THEN** every response keeps its status and no traffic or near/over key is written for those requests

#### Scenario: Redis returns
- **WHEN** Redis was unreachable, becomes reachable again, and tenant `1` sends an allowed request in epoch second `s` of period `p`
- **THEN** `traffic:allowed:{s}` and `traffic:total:allowed:{p}` count that request, and tenant `1`'s set membership reflects its `used` in `p` as stored in Redis
