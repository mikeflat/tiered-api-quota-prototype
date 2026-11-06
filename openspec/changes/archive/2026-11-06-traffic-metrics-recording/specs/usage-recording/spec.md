# Spec Delta

## MODIFIED Requirements

### Requirement: One writer applies each event with one atomic Redis call
A single background writer SHALL take events from the queue in order and apply each with exactly one atomic Redis script call (D5). An allowed event SHALL increment `quota:used:{tenantId}:{p}`; a rejected event SHALL increment `quota:rejected:{tenantId}:{p}`. The same call SHALL also write the traffic metrics of the event (global per-second counts, period totals and near/over set membership, see the `traffic-metrics` capability); no other key is written. The call SHALL return the tenant's `used` for that period, `0` if none.

#### Scenario: Allowed events increment used
- **WHEN** tenant `1` sends three requests in period `p` and the writer has processed their events
- **THEN** `quota:used:1:{p}` is `3` and `quota:rejected:1:{p}` does not exist

#### Scenario: Rejected event increments rejected and returns used
- **WHEN** `quota:used:1:{p}` is `5` and a rejected event for tenant `1` in period `p` is applied
- **THEN** `quota:rejected:1:{p}` is `1`, `quota:used:1:{p}` stays `5`, and the call returns `5`

#### Scenario: Rejected event for a tenant with no usage
- **WHEN** a rejected event for tenant `2` in period `p` is applied and no `quota:used:2:{p}` exists
- **THEN** the call returns `0` and `quota:used:2:{p}` is still absent

#### Scenario: Return value after an allowed event
- **WHEN** `quota:used:1:{p}` is `9` and an allowed event for tenant `1` in period `p` is applied
- **THEN** the call returns `10`

#### Scenario: Tenants are counted separately
- **WHEN** tenant `1` sends two requests and tenant `2` sends one request in the same period
- **THEN** `quota:used:1:{p}` is `2` and `quota:used:2:{p}` is `1`

#### Scenario: Script unknown to Redis
- **WHEN** Redis has lost its script cache (for example after `SCRIPT FLUSH` or a restart) and an event is applied
- **THEN** the event is still counted, together with its traffic metrics, with a single successful call from the writer's point of view

#### Scenario: Counters and metrics change together
- **WHEN** an allowed event of tenant `1` in epoch second `s` of period `p` is applied
- **THEN** after that one call `quota:used:1:{p}`, `traffic:allowed:{s}` and `traffic:total:allowed:{p}` have each grown by `1` and tenant `1`'s near/over membership matches the returned `used`

#### Scenario: Only expected keys are written
- **WHEN** an allowed event of tenant `1` in epoch second `s` of period `p` is applied to an empty Redis and brings `used` below the near boundary
- **THEN** the only keys in Redis are `quota:used:1:{p}`, `traffic:allowed:{s}` and `traffic:total:allowed:{p}`

## ADDED Requirements

### Requirement: The writer resolves the tenant's current limit for each event
When applying an event, the writer SHALL look up the tenant's tier at that moment and pass its limit and near-limit boundary to the record call, and SHALL use the same limit for the blocking decision of that event (FR-6, FR-11). The lookup SHALL need no I/O.

#### Scenario: Limit and boundary passed for a Bronze tenant
- **WHEN** the Bronze limit is `100`, the near threshold is `80` %, and an event of Bronze tenant `1` is applied
- **THEN** the record call is evaluated with limit `100` and near boundary `80`

#### Scenario: Limit and boundary passed for a Gold tenant
- **WHEN** the Gold limit is `10000`, the near threshold is `80` %, and an event of Gold tenant `14` is applied
- **THEN** the record call is evaluated with limit `10000` and near boundary `8000`

#### Scenario: Redis down does not affect the lookup
- **WHEN** Redis is unreachable and an event is applied
- **THEN** the lookup succeeds, the record call fails, and the event is counted as failed without blocking anyone
