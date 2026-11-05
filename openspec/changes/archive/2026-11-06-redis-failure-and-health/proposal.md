# Proposal

## Why

Backlog item 6, `redis-failure-and-health` (docs/04 backlog.md). Earlier changes already keep the request path free of Redis: blocked tenants stay blocked, unblocked tenants are served, and failed or dropped events are counted in memory. But an outage is invisible: nothing is logged, `/actuator/health` does not mention Redis, and no test stops Redis in the middle of live traffic and checks the gateway's behaviour through the outage and back. The dashboard's "Enforcement degraded" banner (FR-21, scenario S7) needs a health signal to react to, and operators need log lines that say counting has stopped and how many events were lost.

## What Changes

- **Redis component in health (FR-17, D10).** `GET /actuator/health` reports Redis as a component (`components.redis.status` `UP` / `DOWN`), with status only and no details. The Redis check answers within a short timeout (the existing 1 s Redis command timeout), so a hung Redis never hangs the health call.
- **BREAKING (health contract): overall status follows Redis.** With Redis unreachable, `GET /actuator/health` answers `503` with `"status":"DOWN"` and `components.redis.status` `DOWN` (built-in aggregation, the decision recorded for this change). Up to now it always answered `200` `UP` without Redis.
- **Liveness and readiness independent of Redis (NFR-4, D10).** New probe endpoints `GET /actuator/health/liveness` and `GET /actuator/health/readiness` report the gateway process only and stay `200` `UP` while Redis is down. The gateway container `HEALTHCHECK` moves to the liveness endpoint, so a Redis outage never marks the gateway container unhealthy and start scripts keep working without Redis.
- **Outage logging (FR-8, D6).** The usage writer logs one warning when recording starts failing (Redis unreachable: no new blocks, counting paused) and one info line when recording succeeds again. Nothing else is logged during the outage: no line per failed event.
- **No tracking of lost events.** While Redis is down, events are simply not counted and enforcement relies only on the current blocked set. The gateway does not report how many events were lost: no periodic lost-event report, no lost-event count in the recovery line, no new setting. The in-memory recorded/dropped/failed counts from earlier changes stay as they are; they are not logged. **Deviation:** FR-6b and backlog item 6 mention logged drops; this change does not log them, by decision for this change.
- **Redis stopped mid-run test (FR-8, NFR-9, S7).** A Testcontainers integration test runs traffic, stops Redis mid-run and verifies: requests keep their status and latency, tenants already blocked stay blocked, a tenant that crosses its limit during the outage is not newly blocked, health is `DOWN` with Redis `DOWN` while liveness and readiness are `UP`, the outage is logged once; after Redis returns, health is `UP` again, recovery is logged, counting resumes and over-limit tenants are blocked again, all without a restart.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `gateway-runtime`: health reports Redis as a component and goes `DOWN` (`503`) when Redis is unreachable; new liveness and readiness probes that do not depend on Redis; health shows component statuses without details; the container healthcheck uses liveness.
- `usage-recording`: the start and the end of a Redis outage are each logged once; events of the outage are not counted and lost events are not reported.
- `quota-enforcement`: the Redis-unreachable requirement covers an outage that starts mid-run, states that no tenant is newly blocked during it, and that blocking resumes after Redis returns.
- `stack-runtime`: the Redis outage scenarios expect liveness `UP` and `/actuator/health` `DOWN` instead of `/actuator/health` `UP`; starting the gateway without Redis waits for the liveness-based container health.

## Impact

- **Gateway code:** `:app` (`UsageRecorder` outage transitions, a new outbound alerts port), `:adapters:infra` (SLF4J implementation of that port), `:bootstrap` (wiring, `application.yaml` health settings), `gateway/Dockerfile` (`HEALTHCHECK` path).
- **HTTP contract:** `/actuator/health` may now answer `503`; new `/actuator/health/liveness` and `/actuator/health/readiness`. The dashboard proxy forwards `/actuator/health` unchanged (the `503` body included); the dashboard's handling of it comes with `dashboard-polling-and-states`.
- **Tests:** existing tests that expect `/actuator/health` `200` without Redis (`HealthEndpointTest`, `BusinessEndpointTest`, `ServerPortTest`) switch to liveness or a Testcontainers Redis; new health and mid-run outage integration tests.
- **Docs:** `gateway/README.md` health and usage-recording sections.
- **Dependencies:** none new (Actuator's built-in Redis health indicator, Spring Data Redis already present).

## Out of scope

- Dashboard banners and polling (backlog items 12–15); snapshot "metrics unavailable" (item 9).
- Global traffic metrics and near/over sets (item 7).
- Tracking or reporting events lost during an outage (counts in logs, periodic reports).
- Separate Redis monitoring and alerting, a metrics endpoint or exporter for dropped/failed counts (requirements §6: separate Redis monitoring is production direction; only `/actuator/health` is exposed).
- Redis high availability or clustering, multiple gateway instances, retrying or buffering lost events, loading the blocked set from Redis after a restart (requirements §6, §7).
