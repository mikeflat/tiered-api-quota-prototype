# usage-recording Specification

## Purpose

Defines how the quota gateway counts business requests per tenant and quota period in Redis without putting Redis on the request path: the usage event, the bounded queue, the single writer and its one atomic Redis call per event, the counter keys and their expiry, and behaviour while Redis is unavailable.

## Requirements

### Requirement: Admitted business requests produce a usage event
For every business request that passes the tenant guard, the gateway SHALL create one usage event holding the tenant id, the outcome (allowed or rejected), the period index `p = floor(epochSeconds / periodSeconds)` and the epoch second, all taken from the clock when the request arrives (FR-6, FR-6a). Until quota enforcement exists, every such event is allowed. Requests rejected by the tenant guard SHALL produce no event.

#### Scenario: Allowed event for a known tenant
- **WHEN** tenant `1` sends `GET /api/v1/resource` at epoch second `1800000030` with a 60 s period
- **THEN** one allowed event is recorded for tenant `1` with period index `30000000` and second `1800000030`

#### Scenario: Period index on the boundary
- **WHEN** a request arrives at epoch second `1800000060` with a 60 s period
- **THEN** its event has period index `30000001`

#### Scenario: Guard rejection is not counted
- **WHEN** a client sends `GET /api/v1/resource` with `X-Tenant-Id: 999` and no tenant `999` exists
- **THEN** the response is `400` and no usage event is created

### Requirement: Recording never blocks or delays the request
The request thread SHALL only hand the event to a bounded in-memory queue and continue; it SHALL NOT wait for Redis, for the writer, or for free queue space (FR-4, FR-6b, NFR-2). The business response SHALL be the same whether recording succeeds, fails or is dropped.

#### Scenario: Response does not wait for Redis
- **WHEN** Redis is unreachable and a known tenant sends `GET /api/v1/resource`
- **THEN** the response is `200` with the mock payload, without waiting for a Redis timeout

#### Scenario: Full queue does not block
- **WHEN** the queue is full and a known tenant sends a request
- **THEN** the response is `200` and the request thread returns without waiting

### Requirement: Events are dropped and counted when the queue is full
When the event queue is at capacity, the gateway SHALL drop the new event and increment an in-memory dropped-events count (FR-6b, D7a). Dropped events are never recorded in Redis.

#### Scenario: Overflow is counted
- **WHEN** the queue capacity is `2`, the writer is busy with an earlier event, and three more events are offered
- **THEN** two events wait in the queue, one is dropped, and the dropped-events count is `1`

### Requirement: Queue capacity is configurable
The event queue capacity SHALL be set by `USAGE_QUEUE_CAPACITY`, default `10000` (NFR-8). A value that is not a whole number of at least 1 SHALL stop startup with an error naming the setting and the value, like the other quota settings.

#### Scenario: Default capacity
- **WHEN** the gateway starts without `USAGE_QUEUE_CAPACITY`
- **THEN** the queue capacity is `10000`

#### Scenario: Invalid capacity
- **WHEN** the gateway starts with `USAGE_QUEUE_CAPACITY=0`
- **THEN** startup fails with a non-zero exit and an error naming the queue capacity setting and `0`

### Requirement: One writer applies each event with one atomic Redis call
A single background writer SHALL take events from the queue in order and apply each with exactly one atomic Redis script call (D5). An allowed event SHALL increment `quota:used:{tenantId}:{p}`; a rejected event SHALL increment `quota:rejected:{tenantId}:{p}`; no other key is written. The call SHALL return the tenant's `used` for that period, `0` if none.

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
- **THEN** the event is still counted with a single successful call from the writer's point of view

### Requirement: Events count toward the period they were created in
The writer SHALL use the event's own period index for the keys, not the period current when the event is processed, so an event processed after a boundary still counts toward its own period (FR-6a). A new period SHALL start from zero for every tenant because its keys are new, with no reset job (FR-3, D4).

#### Scenario: Event processed after the boundary
- **WHEN** an allowed event for tenant `1` with period index `p` is processed after period `p + 1` has started
- **THEN** `quota:used:1:{p}` is incremented and `quota:used:1:{p+1}` is not

#### Scenario: New period starts from zero
- **WHEN** tenant `1` has `used` `7` in period `p` and sends its first request in period `p + 1`
- **THEN** the call for that event returns `1`

### Requirement: Counter keys expire after two periods
Each counter key SHALL get a time-to-live of two periods (`2 × periodSeconds`) when the script creates it, and the TTL SHALL NOT be extended by later increments, so old periods disappear without cleanup jobs (D4, architecture §4).

#### Scenario: TTL on a new key
- **WHEN** the first allowed event for tenant `1` in period `p` is applied with a 60 s period
- **THEN** `quota:used:1:{p}` has a TTL greater than `0` and at most `120` seconds

#### Scenario: Later increments keep the original TTL
- **WHEN** a second allowed event for tenant `1` in period `p` is applied some time after the first
- **THEN** the TTL of `quota:used:1:{p}` is not reset to the full two periods

### Requirement: Redis failures do not stop recording
When a Redis call fails or times out, the writer SHALL discard that event, increment an in-memory failed-events count and continue with the next event; it SHALL NOT retry the event or terminate (groundwork for FR-8, D6). When Redis becomes reachable again, later events SHALL be counted without a gateway restart. Each Redis call SHALL time out after a configurable short duration (default 1 s).

#### Scenario: Redis unavailable
- **WHEN** Redis is unreachable and a known tenant sends requests
- **THEN** every response is `200`, the failed-events count grows, and nothing is counted in Redis for those requests

#### Scenario: Redis returns
- **WHEN** Redis was unreachable, becomes reachable again, and tenant `1` then sends a request in period `p`
- **THEN** that request is counted in `quota:used:1:{p}` without restarting the gateway

### Requirement: Writer lifecycle follows the gateway
The writer SHALL start when the gateway starts and stop when it shuts down. Startup SHALL NOT require Redis to be reachable and SHALL NOT connect to Redis. Events still queued at shutdown MAY be lost.

#### Scenario: Start without Redis
- **WHEN** the gateway starts with no Redis server available
- **THEN** startup succeeds and `GET /actuator/health` returns `200` with `"status":"UP"`

#### Scenario: Shutdown with Redis unreachable
- **WHEN** the gateway is shut down while Redis is unreachable and events are queued
- **THEN** the gateway stops without waiting for the queued events to be recorded
