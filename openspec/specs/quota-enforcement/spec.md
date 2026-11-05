# quota-enforcement Specification

## Purpose

Defines how the quota gateway enforces each tenant's per-period limit: the in-memory blocked set of tenants known to be over limit, how tenants enter it from recorded usage, the no-I/O quota decision with its `429` response, the reset at the period boundary, and the accepted bounded overshoot.

## Requirements

### Requirement: Quota decision uses only the in-memory blocked set
For a request from a known tenant, the gateway SHALL decide in memory, with no Redis call or other I/O, whether the tenant is in the blocked set of the current period (FR-4, NFR-2, D5). A tenant not in that set SHALL be served as before (`200` with the mock payload).

#### Scenario: Tenant under its limit is served
- **WHEN** tenant `1` has not reached its limit in the current period and sends `GET /api/v1/resource`
- **THEN** the response is `200` with the mock payload

#### Scenario: Decision does not wait for Redis
- **WHEN** Redis is unreachable and a tenant that is not blocked sends `GET /api/v1/resource`
- **THEN** the response is `200` without waiting for a Redis timeout

### Requirement: Blocked tenants get 429 with Retry-After
A request from a tenant in the current period's blocked set SHALL be answered `429 Too Many Requests` without reaching the business handler (FR-5). The response SHALL carry `Retry-After` with the whole seconds until the next period boundary, `(p + 1) × periodSeconds − epochSecond` at arrival, which is always at least `1`.

#### Scenario: Blocked tenant is rejected
- **WHEN** tenant `1` is in the blocked set of the current period and sends `GET /api/v1/resource`
- **THEN** the response status is `429` and the mock payload is not returned

#### Scenario: Retry-After in the middle of a period
- **WHEN** a blocked tenant sends a request at epoch second `1800000030` with a 60 s period
- **THEN** the `Retry-After` header is `30`

#### Scenario: Retry-After in the last second of a period
- **WHEN** a blocked tenant sends a request at epoch second `1800000059` with a 60 s period
- **THEN** the `Retry-After` header is `1`

#### Scenario: Other tenants are not affected
- **WHEN** tenant `1` is blocked and tenant `2` is not, and both send `GET /api/v1/resource`
- **THEN** tenant `1` gets `429` and tenant `2` gets `200`

### Requirement: 429 response has a minimal problem body without usage data
A `429` SHALL have `Content-Type: application/problem+json` and a body with `status` `429`, `title` `Too Many Requests` and a `detail` stating that the tenant's quota for the current period is exhausted. Neither the body nor the headers SHALL carry used, limit or remaining-quota values (FR-5, FR-7).

#### Scenario: Problem body of a rejection
- **WHEN** a blocked tenant sends `GET /api/v1/resource`
- **THEN** the `Content-Type` is `application/problem+json` and the body has `"status":429` and `"title":"Too Many Requests"`

#### Scenario: No usage data in a rejection
- **WHEN** a blocked tenant sends `GET /api/v1/resource`
- **THEN** the response has no header whose name starts with `X-RateLimit` or `RateLimit`, no `X-Quota` header, and the body contains no used, limit or remaining value

### Requirement: Tenants are blocked when recorded usage reaches the current limit
After applying an allowed event, the writer SHALL add the tenant to the blocked set when the returned `used` is at least the limit of the tenant's tier at that moment and the event's period is still the current period (FR-6, D7). Rejected events and events of a past period SHALL NOT block anyone.

#### Scenario: Usage reaches the limit
- **WHEN** the Bronze limit is `5`, tenant `1` is Bronze, and the writer applies an allowed event that returns `used` `5`
- **THEN** tenant `1` is in the blocked set and its next request gets `429`

#### Scenario: Usage below the limit
- **WHEN** the Bronze limit is `5` and the writer applies an allowed event for Bronze tenant `1` that returns `used` `4`
- **THEN** tenant `1` is not blocked and its next request gets `200`

#### Scenario: Limit depends on the tier
- **WHEN** the Bronze limit is `5`, the Silver limit is `50`, and allowed events of Bronze tenant `1` and Silver tenant `2` each return `used` `5`
- **THEN** tenant `1` is blocked and tenant `2` is not

#### Scenario: Late event of a past period
- **WHEN** an allowed event of period `p` returns `used` at or above the limit but is applied after period `p + 1` has started
- **THEN** the tenant is not blocked in period `p + 1`

### Requirement: Blocked requests consume no quota
A request answered `429` SHALL produce one rejected usage event and SHALL NOT increase the tenant's `used` (FR-5, FR-6).

#### Scenario: Rejections are counted separately
- **WHEN** tenant `1` is blocked in period `p` with `quota:used:1:{p}` at `5` and sends three more requests that get `429`
- **THEN** after the writer has processed them `quota:rejected:1:{p}` is `3` and `quota:used:1:{p}` is still `5`

### Requirement: Blocked set is discarded at the period boundary
The blocked set SHALL belong to one period index. Once the period changes, no tenant blocked in an earlier period SHALL be treated as blocked, without any scheduled reset job (FR-3, FR-6a, D4, S3).

#### Scenario: Blocked tenant is served again in the next period
- **WHEN** tenant `1` was blocked in period `p` and sends a request after period `p + 1` has started
- **THEN** the response is `200` and the request is counted in `quota:used:1:{p+1}`

#### Scenario: Tenant can be blocked again in the new period
- **WHEN** tenant `1` was blocked in period `p` and its usage in period `p + 1` reaches its limit
- **THEN** tenant `1` is blocked again in period `p + 1`

### Requirement: Overshoot is bounded
Requests that arrive between the request that crosses the limit and the writer applying its event MAY be served, so `used` MAY end above the limit (FR-6, NFR-5). Once the writer has applied an event reaching the limit, every later request of that tenant in the same period SHALL get `429`.

#### Scenario: Blocking after the limit
- **WHEN** the Bronze limit is `5` and Bronze tenant `1` sends requests one after another, waiting for each to be recorded
- **THEN** the first `5` requests get `200` and the sixth gets `429`

#### Scenario: Overshoot equals the requests the writer has not applied yet
- **WHEN** the Bronze limit is `5`, the writer is stalled, Bronze tenant `1` sends `8` requests, and the writer then catches up
- **THEN** all `8` requests get `200`, `used` is `8`, and the next request gets `429`

#### Scenario: Counts stay consistent under a burst
- **WHEN** the Bronze limit is `5` and Bronze tenant `1` sends a concurrent burst of requests, and the writer has processed all their events
- **THEN** at least `5` requests got `200` and at least one got `429`, `quota:used:1:{p}` equals the number of `200` responses, and `quota:rejected:1:{p}` equals the number of `429` responses

### Requirement: Blocked tenants stay blocked while Redis is unreachable
Because the decision uses only memory, tenants already in the current period's blocked set SHALL keep getting `429` while Redis is unreachable, until the period ends; no tenant SHALL be newly blocked while nothing can be recorded (FR-8, D6). Logging and health reporting of the outage are not part of this capability.

#### Scenario: Redis stops after a tenant was blocked
- **WHEN** tenant `1` is blocked, Redis becomes unreachable, and tenant `1` sends another request in the same period
- **THEN** the response is `429` with `Retry-After`, without waiting for a Redis timeout

#### Scenario: Unblocked tenants keep being served
- **WHEN** Redis is unreachable and tenant `2`, which is not blocked, sends requests
- **THEN** every response is `200`

### Requirement: Restart starts with an empty blocked set
The blocked set SHALL live only in gateway memory; after a restart it is empty and tenants are blocked again on their next recorded event that reaches the limit (requirements §7, accepted limitation).

#### Scenario: Restart while a tenant is over its limit
- **WHEN** tenant `1` is over its limit in period `p`, the gateway restarts, and tenant `1` sends a request in period `p`
- **THEN** that request gets `200`, and after the writer applies its event tenant `1` is blocked again
