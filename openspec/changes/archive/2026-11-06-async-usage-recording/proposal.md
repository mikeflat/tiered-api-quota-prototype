# Proposal

## Why

Backlog item 4, `async-usage-recording` (docs/04 backlog.md). Every known tenant gets `200` today, but nothing is counted: there is no record of how many requests each tenant made in the current period. Quota enforcement (item 5) needs Redis to tell the gateway when a tenant's `used` reaches its limit, and that requires a recording path that never touches the request thread. This change adds that path — a bounded in-memory event queue drained by one writer thread that applies each event to Redis with one Lua call — before the blocked set is built on top of it.

## What Changes

- Every business request that passes the tenant guard produces a usage event (tenant, outcome allowed/rejected, period index, epoch second), captured from the clock when the request arrives (FR-6, FR-6a). In this change every admitted request is `allowed`; `rejected` events start with item 5.
- The request thread only offers the event to a bounded queue and returns; it never blocks and never performs I/O (FR-4, FR-6b, NFR-2). When the queue is full the event is dropped and a dropped-events counter is incremented (FR-6b, D7a).
- One background writer thread takes events in order and applies each to Redis with a single Lua script call (`EVALSHA`, falling back to loading the script when Redis does not know it): an allowed event increments `quota:used:{tenantId}:{p}`, a rejected event increments `quota:rejected:{tenantId}:{p}`; keys get a TTL of two periods when created; the script returns the tenant's `used` for the event's period (FR-6, D5, D7, architecture §4).
- A failed Redis call does not stop the writer: the event is lost, a failed-events counter is incremented and the writer continues with the next event (groundwork for FR-8). The business response is unaffected whether Redis is up or down.
- The queue capacity is configurable without code changes (`USAGE_QUEUE_CAPACITY`, default 10,000) and invalid values stop startup (NFR-8). The Redis command and connect timeouts are made short and configurable so a dead Redis cannot hold the writer for a minute per event.
- The writer starts with the gateway and stops when it shuts down; startup still succeeds without Redis (the gateway does not connect to Redis at startup).
- Update `gateway/README.md` and the `application.yaml` / `docker-compose.yml` comments that say Redis is not used yet.

## Capabilities

### New Capabilities
- `usage-recording`: how the gateway counts business requests per tenant and period in Redis — the usage event, the non-blocking bounded queue with drop counting, the single writer applying one atomic Redis call per event, the per-period counter keys and their expiry, the value returned to the gateway, and behaviour while Redis is unavailable. Items 5 (blocked set fed by the returned `used`), 6 (logging, health) and 7 (global traffic metrics and near/over sets in the same script) extend this capability.

### Modified Capabilities
- None. `business-api` behaviour is unchanged (same `200` / `400` responses, still no I/O on the request path, still works without Redis), and `gateway-runtime` already requires startup and health without Redis, which this change keeps.

## Impact

- **Code**: `gateway/domain` (usage event, outcome and period-index calculation), `gateway/app` (the `UsageRecorder` that hands events to an injected executor and keeps the counters, the outbound `UsageCounter` port and its failure exception, `AdmitBusinessCall` emitting the allowed event), `gateway/adapters/infra` (Redis adapter and the Lua script resource — the module's first classes), `gateway/bootstrap` (the bounded single-thread writer executor, `UsageRecordingProperties`, wiring in `InfraConfiguration` / `AppConfiguration`, Redis timeouts in `application.yaml`, integration tests with Testcontainers Redis), `docker-compose.yml` (pass-through of `USAGE_QUEUE_CAPACITY`), `gateway/README.md`.
- **APIs**: none. No new endpoints or headers; response codes and bodies are unchanged.
- **Dependencies**: none new. Spring Data Redis is already on `:adapters:infra` and `:bootstrap`; Testcontainers is already in the version catalog. Gateway tests in `:adapters:infra` and `:bootstrap` that use Redis need Docker.
- **Other components**: none. The dashboard reads nothing new yet (admin snapshot is item 9); the generator is unchanged.
- **Requirements covered**: FR-6 (asynchronous, one Redis call per request, `used` returned — the comparison with the limit is item 5), FR-6a (event carries its period and second, counted in that period even if processed after the boundary), FR-6b (bounded queue, drop and count, request threads never block — logging the drop count is item 6), FR-4 / NFR-2 (no I/O on the request path), groundwork for FR-8 and NFR-4 (Redis failure does not affect requests). Decisions: D4 (UTC-epoch fixed window via key naming and TTL, no reset jobs), D5 (async recording, one Lua call per request, only allowed requests consume), D7 (counting only in Redis), D7a (bounded queue, drop on overflow).

## Out of scope

- The blocked set, `429` and `Retry-After`, comparing `used` with the tenant's limit (item 5). Every known tenant still gets `200`.
- Logging Redis failures and dropped events, Redis in `/actuator/health`, liveness/readiness groups (item 6). This change only counts drops and failures in memory.
- Global per-second and per-period traffic counters and near/over set maintenance in the script (item 7).
- Admin API and snapshot reads of the counters (items 8–10).
- Batching or aggregating events per tenant, multiple writer threads, draining the queue on shutdown, persisting events across restarts, a message broker (requirements §6; architecture §8 "Scale").
- Load testing at production rate (requirements §6).
