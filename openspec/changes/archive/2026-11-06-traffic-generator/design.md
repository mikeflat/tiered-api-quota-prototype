# Design

## Context

`/generator` is a skeleton (see proposal.md - Why): `src/main.ts` validates `GATEWAY_URL`, logs a startup line, holds the event loop with an idle interval and stops on `SIGTERM`/`SIGINT`; `src/config.ts` parses the gateway URL. Node ≥ 22.18 runs the TypeScript sources by type stripping (`erasableSyntaxOnly`, `.ts` import suffixes), there are no runtime dependencies, and Vitest runs `src/*.test.ts`. The Dockerfile copies only `package.json` and `src/` and runs as `node`; the compose service has no volumes yet. The gateway mounts `config/tenants.json` at `/config/tenants.json`.

The generator is exempt from Clean Architecture (project rule): a flat `src/main.ts` plus a few flat modules, pure helpers as plain functions so they can be unit-tested. Project rules that still apply: no code comments, snake_case test names. The skeleton's existing JSDoc comments, the inline comment in `main.ts` and the sentence-style test names are fixed while those files are touched.

## Goals / Non-Goals

**Goals:**
- Keep the hot path trivial: one timer tick, a pacer and a weighted pick per request; no per-request logging or allocation beyond the `fetch` itself.
- Every failure mode of the gateway (refused, DNS failure, hang, unexpected status) maps to a counted outcome; nothing can reject an unhandled promise or grow memory without bound.
- All logic except the wiring in `main.ts` is testable without timers, sockets or a gateway.

**Non-Goals:**
- Precise rate control under heavy event-loop pressure; ±5 % over 10 s is enough for a demo.
- Reloading the config while running; per-tenant rates or time-varying shapes.
- Collecting latency statistics.

## Decisions

### 1. Flat module layout

| Module | Kind | Responsibility |
|---|---|---|
| `src/config.ts` | pure | `readGatewayUrl`, `describeGatewayUrl` (existing), `readFilePaths(env)` → tenants and traffic config paths with defaults resolved from `import.meta.url` (`../../config/tenants.json`, `../traffic.json`), so the defaults work from any working directory and inside the image (`/app/src` → `/app/traffic.json`) |
| `src/tenants.ts` | pure + thin I/O | `parseTenantIds(text, path)` → `number[]`; `loadTenantIds(path)` reads the file |
| `src/traffic-config.ts` | pure + thin I/O | `parseTrafficConfig(text, path, tenantIds)` → `TrafficConfig { ratePerSecond, maxInFlight, requestTimeoutMs, weightedTenants: { id, weight }[] }`; `loadTrafficConfig` reads the file |
| `src/pacing.ts` | pure | `createPacer(ratePerSecond, startMs)` → `{ takeDue(nowMs): number }` |
| `src/weighted-pick.ts` | pure | `createWeightedPicker(weightedTenants, random)` → `() => number` |
| `src/outcome.ts` | pure | `Outcome` union and `classifyStatus(status)`, `classifyError(error)` |
| `src/send-request.ts` | I/O | `sendRequest(fetchFn, endpoint, tenantId, timeoutMs, stopSignal)` → `Promise<Outcome>`, never rejects |
| `src/summary.ts` | pure | `createSummary()` → `{ recordOutcome, recordSkipped, takeLine(): string }` |
| `src/main.ts` | wiring | load settings, log startup line, run the tick and summary intervals, shut down |

Loading errors are thrown as `Error` with a message naming the path and the field; `main.ts` prints the message to stderr and sets exit code 1 before any timer starts (same pattern as the existing `GATEWAY_URL` check). Alternative considered: a schema library (zod) — rejected to keep zero runtime dependencies for a handful of fields.

### 2. Pacing from wall-clock time with a one-second catch-up cap

A 20 ms `setInterval` tick asks the pacer how many requests are due: `due = floor((now − start) × rate / 1000) − started`. If `due` exceeds `rate` (one second's worth, e.g. after the event loop stalled or the laptop slept), the pacer moves its baseline forward so only `rate` are released and the backlog is forgotten. Using elapsed time rather than "N per tick" makes the rate independent of timer resolution (≈15 ms on Windows) and drift. Alternative considered: one `setTimeout` per request at `1000/rate` ms — rejected because timers below a few ms are unreliable and 200 timers/s add overhead for no benefit.

### 3. Weighted pick by cumulative weights and binary search

Tenants with weight 0 are dropped when the config is parsed; the picker builds a cumulative array once and does `random() × total` + binary search per request. `random` is injected (`Math.random` in `main.ts`) so tests can use a seeded or scripted source. Alternative: alias method — O(1) but unnecessary for 15 tenants.

### 4. Bounded in-flight requests, skipped instead of queued

`main.ts` keeps an in-flight counter. When a request is due and the counter equals `maxInFlight` (default 100), it is counted as `skipped` and dropped. This bounds sockets and memory when the gateway hangs, while the pacer keeps its schedule so traffic resumes at the configured rate the moment answers return. Alternative considered: an unbounded queue — rejected (memory grows with the outage, then a burst hits the gateway when it returns).

### 5. Request sending and outcome classification

`sendRequest` calls the global `fetch` (undici, keep-alive by default) with `X-Tenant-Id` and `signal: AbortSignal.any([AbortSignal.timeout(requestTimeoutMs), stopSignal])`, then drains the body with `arrayBuffer()` so the connection returns to the pool. `200` → `ok`, `429` → `rejected`, any other status → `error` with reason `status <n>`. A thrown error becomes `error` with a short reason: `timeout` for `TimeoutError`, `stopped` for the stop abort, otherwise `error.cause.code` (e.g. `ECONNREFUSED`, `ENOTFOUND`) or the error name. The function catches everything, so the tick never sees a rejected promise. The endpoint URL (`new URL('api/v1/resource', base)` with the base path normalised to end in `/`) is built once at startup.

### 6. Summary counters per interval

`summary.ts` holds counters `sent`, `ok`, `rejected`, `errors`, `skipped` and `lastErrorReason`. `sent` counts requests actually started (not skipped). `takeLine()` formats `summary: sent=… 200=… 429=… errors=… skipped=…` plus ` last-error=<reason>` when `errors > 0`, then resets. A 10 s `setInterval` in `main.ts` logs it with the usual ISO timestamp prefix. Outcomes with reason `stopped` are not recorded, so shutdown does not inflate the error count.

### 7. Shutdown

On `SIGTERM`/`SIGINT`: clear both intervals, abort the shared stop `AbortController` (in-flight `fetch`es reject at once), log `takeLine()` and `generator stopped (<signal>)`, set exit code 0. With no timers left and sockets aborted, the event loop drains and the process exits without `process.exit`. Keep-alive sockets of the undici pool are unref'd by Node, so they do not hold the process.

### 8. Configuration files and defaults

`generator/traffic.json` (all keys validated, unknown keys rejected to catch typos):

```json
{
  "ratePerSecond": 200,
  "defaultWeight": 1,
  "maxInFlight": 100,
  "requestTimeoutMs": 2000,
  "weights": { "1": 300, "2": 300, "3": 300, "4": 90, "5": 90, "6": 40, "7": 40, "8": 40,
               "9": 1500, "10": 1500, "11": 900, "12": 400, "13": 400, "14": 4000, "15": 2100 }
}
```

The weights are chosen to sum to 12,000 = 200 req/s × 60 s, so each weight reads as the expected requests per period:

| Tenants | Tier (limit) | Expected / period | Expected state |
|---|---|---|---|
| 1, 2, 3 | Bronze (100) | 300 | over (blocked after ~20 s) |
| 4, 5 | Bronze (100) | 90 | near (90 %) |
| 6, 7, 8 | Bronze (100) | 40 | ok |
| 9, 10 | Silver (1,000) | 1,500 | over (blocked after ~40 s) |
| 11 | Silver (1,000) | 900 | near |
| 12, 13 | Silver (1,000) | 400 | ok |
| 14 | Gold (10,000) | 4,000 | ok |
| 15 | Gold (10,000) | 2,100 | ok |

Tiers are not read by the generator (the gateway owns them; an operator may change them at runtime), so the FR-25 check is a unit test over the default file, `config/tenants.json` and the documented default limits.

Environment variables: `GATEWAY_URL` (unchanged), `TENANTS_FILE`, `TRAFFIC_CONFIG_FILE`; blank means default. The Dockerfile copies `traffic.json` and sets `TENANTS_FILE=/config/tenants.json`. `docker-compose.yml` mounts `./config/tenants.json:/config/tenants.json:ro` and `./generator/traffic.json:/app/traffic.json:ro` on the generator; `stop-generator` (`compose stop`) followed by `start-generator` restarts the same container, which re-reads the mounted file.

Startup line: `generator started: gateway=<url>, tenants=<n>, rate=<r> req/s, max-in-flight=<m>`.

### 9. Tests

- Unit (Vitest, no timers or sockets): `config`, `tenants`, `traffic-config` (each validation branch, defaults, unknown id, default file meets FR-25), `pacing` (steady rate, fractional rates, catch-up cap, no negative due), `weighted-pick` (distribution with a seeded generator, weight 0 excluded, boundary values of `random`), `outcome` (status and error mapping), `summary` (format, reset, last-error only with errors, `stopped` ignored).
- `send-request` with a fake `fetch` (status, body drained, header and URL, timeout and stop aborts, thrown errors never escape).
- `main` as a child process (existing pattern) against a `node:http` server created inside the test on port 0 (a test fixture, not a project service): requests arrive with `X-Tenant-Id`; a temp tenants/traffic file restricts ids; invalid files exit 1 naming the path; `SIGINT` logs a final summary and stop line and exits 0, also while the server never answers; with no server listening the process keeps running.

## Risks / Trade-offs

- [Near tenants overshoot by chance: Bronze 90/period has σ ≈ 9.5, so some periods end "over"] → acceptable for starting values; tuning belongs to `calibration-and-demo`.
- [Requests to blocked tenants keep flowing, so most traffic of tenants 1–3 and 9–10 is `429`] → intended: it drives the rejected rate on the dashboard (S2); the weights document it.
- [Inside compose a stopped gateway can make DNS lookups slow rather than refused] → bounded by `requestTimeoutMs` and `maxInFlight`; summaries show `ENOTFOUND`/`timeout`.
- [A 20 ms tick under event-loop pressure delays requests] → the pacer catches up from wall-clock time within the one-second cap.
- [Stack-level scenarios (compose mounts, docker stop, restart applies new rate) can't be run here because of the no-services test rule] → covered by unit/child-process tests where possible; the rest is left to the user's manual check and listed in tasks.

## Migration Plan

No data or API migration. The new compose volumes take effect on the next `start`; the generator image must be rebuilt once (the start script rebuilds changed sources). Rollback = revert the change; the gateway is unaffected.
