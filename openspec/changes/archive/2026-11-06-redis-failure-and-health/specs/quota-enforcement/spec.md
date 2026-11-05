## MODIFIED Requirements

### Requirement: Blocked tenants stay blocked while Redis is unreachable
Because the decision uses only memory, tenants already in the current period's blocked set SHALL keep getting `429` while Redis is unreachable, until the period ends; no tenant SHALL be newly blocked while nothing can be recorded (FR-8, D6). This SHALL hold for an outage that starts mid-run, and blocking SHALL resume without a restart once Redis is reachable again. Logging and health reporting of the outage are specified in `usage-recording` and `gateway-runtime`.

#### Scenario: Redis stops after a tenant was blocked
- **WHEN** tenant `1` is blocked, Redis becomes unreachable, and tenant `1` sends another request in the same period
- **THEN** the response is `429` with `Retry-After`, without waiting for a Redis timeout

#### Scenario: Unblocked tenants keep being served
- **WHEN** Redis is unreachable and tenant `2`, which is not blocked, sends requests
- **THEN** every response is `200`

#### Scenario: No new blocks during the outage
- **WHEN** the Bronze limit is `5`, Bronze tenant `2` has `used` `3` in period `p`, Redis becomes unreachable, and tenant `2` sends `10` more requests in period `p`
- **THEN** all `10` requests get `200` and tenant `2` is not in the blocked set
- **AND** once Redis is reachable again, `quota:used:2:{p}` is still `3` if Redis refused the calls, and at least `3` if Redis hung (record calls that timed out on the gateway may still be applied by Redis when it answers again)

#### Scenario: Calls that timed out during a hang are applied late
- **WHEN** Bronze tenant `2` has `used` `3` in period `p`, Redis hangs (accepts connections but does not answer), tenant `2` sends `10` requests whose record calls time out, and Redis then answers again
- **THEN** tenant `2` was not blocked during the hang
- **AND** `quota:used:2:{p}` may be greater than `3`, and the first event recorded after the hang blocks tenant `2` if its returned `used` reaches the limit

#### Scenario: Period ends during the outage
- **WHEN** tenant `1` is blocked in period `p`, Redis is unreachable, and period `p + 1` starts
- **THEN** tenant `1`'s requests in period `p + 1` get `200`, and no tenant is blocked while Redis stays unreachable

#### Scenario: Blocking resumes after Redis returns
- **WHEN** the Bronze limit is `5`, Redis was unreachable and is reachable again, and Bronze tenant `2` sends requests in the current period until its recorded `used` reaches `5`
- **THEN** tenant `2` is blocked and its next request gets `429`, without restarting the gateway
