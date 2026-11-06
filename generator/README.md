# Quota traffic generator

Demo traffic emulator for the Tiered Tenant Quota Prototype. It sends continuous
`GET /api/v1/resource` requests with an `X-Tenant-Id` header to the gateway, paced to a total
rate and spread over the tenants by weight, so the dashboard always shows uneven, live traffic:
some tenants stay ok, some get near their limit and some go over it and receive `429`.

It reads the tenants from the shared `config/tenants.json` (the same file the gateway reads)
and the traffic shape from its own `traffic.json`. It keeps running when the gateway is down,
counts the failures and logs a summary every 10 s.

The generator is a small flat script (`src/main.ts` plus a few flat modules), not production
code. It is deliberately **exempt from the project's Clean Architecture rule**: no layer
folders, ports or lint-enforced layering. Small pure helpers are plain functions so they can be
unit-tested.

## Stack

- Node.js LTS running the TypeScript sources directly (built-in type stripping), Node `>=22.18`
- **No build step**: nothing is compiled or bundled; `tsc` only type-checks
- npm with a committed `package-lock.json`; no runtime dependencies (built-in `fetch` with keep-alive)
- Vitest (node environment) for tests, co-located as `src/*.test.ts`

| File | Purpose |
|---|---|
| `src/main.ts` | Entry point: load settings, log the startup line, start traffic, stop on `SIGTERM` / `SIGINT` |
| `src/generator.ts` | Traffic loop: 20 ms tick, in-flight limit, 10 s summary, shutdown |
| `src/config.ts` | `GATEWAY_URL` → validated URL; `TENANTS_FILE`, `TRAFFIC_CONFIG_FILE` → file paths |
| `src/tenants.ts` | Tenants file → list of tenant ids |
| `src/traffic-config.ts` | `traffic.json` → validated traffic shape |
| `src/pacing.ts` | Requests due from elapsed time, with a one-second catch-up cap |
| `src/weighted-pick.ts` | Weighted random tenant choice |
| `src/send-request.ts` | One request with timeout and stop signal → outcome |
| `src/outcome.ts` | Status / error → `200`, `429` or error with a short reason |
| `src/summary.ts` | Per-interval counters and the summary line |
| `src/test-gateway.fixture.ts` | In-test HTTP server used by the tests (not in the image) |
| `traffic.json` | Default traffic shape |

## Scripts

Install dependencies first:

```bash
npm ci
```

| Command | What it does |
|---|---|
| `npm start` | Run the generator (`node src/main.ts`); stop it with Ctrl+C |
| `npm run typecheck` | Type-check only (`tsc --noEmit`); also rejects syntax Node cannot strip, e.g. `enum` |
| `npm test` | Run the tests once, headless (no gateway or Docker needed) |
| `npm run test:watch` | Run the tests in watch mode |

Sources must use only erasable TypeScript syntax (no `enum`, `namespace` or parameter
properties), and relative imports use the `.ts` extension, as Node requires. The child-process
`SIGINT` test is skipped on Windows, where Node cannot deliver `SIGINT` to a child process; the
same stop behaviour is covered in-process by `generator.test.ts`.

## Configuration

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` (image: `http://gateway:8080`) | Gateway base address, absolute `http` or `https` URL |
| `TENANTS_FILE` | `../config/tenants.json` of this repository (image: `/config/tenants.json`) | Shared tenants file |
| `TRAFFIC_CONFIG_FILE` | `traffic.json` in this folder (image: `/app/traffic.json`) | Traffic shape |

Blank values mean the default. The values are read from the environment (see `.env.example`;
the file is not loaded automatically). Example:

```bash
GATEWAY_URL=http://localhost:9090 npm start
```

### Tenants file

A JSON array of objects with a positive integer `id`; other fields (`name`, `tier`) are
ignored, because tiers belong to the gateway and can change at runtime. An empty array, a
missing or non-integer `id` or a repeated `id` stops the generator.

### Traffic shape (`traffic.json`)

| Field | Required | Default | Meaning |
|---|---|---|---|
| `ratePerSecond` | yes | — | Total requests per second, `> 0` and `<= 5000` |
| `defaultWeight` | no | `1` | Weight of every tenant not listed in `weights`, `>= 0` |
| `weights` | no | `{}` | Tenant id → weight `>= 0`; weight `0` means the tenant gets no traffic |
| `maxInFlight` | no | `100` | Most requests waiting for an answer; due requests beyond it are skipped, not queued |
| `requestTimeoutMs` | no | `2000` | A request without an answer in this time is abandoned and counted as an error |

Unknown keys, ids that are not in the tenants file, and a shape where every tenant has weight 0
stop the generator. Any startup error exits with code `1` and a message naming the file and the
field.

The default weights add up to 12,000 = 200 req/s × 60 s, so each weight reads as the expected
requests per 60 s period. With the default limits (Bronze 100, Silver 1,000, Gold 10,000) and
the 80 % near threshold:

| Tenants | Tier (limit) | Expected / period | Expected state |
|---|---|---|---|
| 1, 2, 3 | Bronze (100) | 300 | over (blocked after about 20 s) |
| 4, 5 | Bronze (100) | 90 | near |
| 6, 7, 8 | Bronze (100) | 40 | ok |
| 9, 10 | Silver (1,000) | 1,500 | over (blocked after about 40 s) |
| 11 | Silver (1,000) | 900 | near |
| 12, 13 | Silver (1,000) | 400 | ok |
| 14 | Gold (10,000) | 4,000 | ok |
| 15 | Gold (10,000) | 2,100 | ok |

Blocked tenants keep receiving traffic, so most of their requests are answered `429`; that is
what drives the rejected rate on the dashboard. Random choice means a "near" tenant sometimes
ends a period over its limit.

## Log output

```
2026-01-01T00:00:00.000Z generator started: gateway=http://localhost:8080, tenants=15, rate=200 req/s, max-in-flight=100
2026-01-01T00:00:10.000Z summary: sent=2000 200=1650 429=350 errors=0 skipped=0
2026-01-01T00:00:20.000Z summary: sent=2000 200=0 429=0 errors=2000 skipped=0 last-error=ECONNREFUSED
2026-01-01T00:00:23.000Z summary: sent=600 200=0 429=0 errors=600 skipped=0 last-error=ECONNREFUSED
2026-01-01T00:00:23.000Z generator stopped (SIGINT)
```

Each summary covers the 10 s since the previous one: `sent` requests started, `200` and `429`
answers, `errors` (other statuses as `status <n>`, connection failures such as `ECONNREFUSED` or
`ENOTFOUND`, and `timeout`), and `skipped` requests that were due while `maxInFlight` requests
were already waiting. `last-error` appears only when there were errors. On `SIGTERM` / `SIGINT`
the generator stops starting requests, abandons the ones in flight, logs the summary of the
unfinished interval and the stop line, and exits with code `0`.

## Docker

Build the image in this folder (only Docker is needed on the host):

```bash
docker build -t quota-generator:local .
```

Run it with the shared tenants file mounted (it listens on no port and keeps running even
without a gateway):

```bash
docker run -d --name quota-generator -v "$(pwd)/../config/tenants.json:/config/tenants.json:ro" quota-generator:local
```

```bash
docker logs quota-generator
```

Stop and remove it (`docker stop` sends `SIGTERM`; the container exits with code `0`):

```bash
docker stop quota-generator
```

```bash
docker rm quota-generator
```

Without a file at `/config/tenants.json` the container exits with code `1` and a message naming
that path. The image runs `node src/main.ts` as the non-root `node` user, contains the default
`traffic.json` and no test files, and has no healthcheck: the generator has no endpoint to
probe, so "running" is its health.

In the stack, `docker-compose.yml` mounts `config/tenants.json` and this folder's `traffic.json`
into the container. To change the traffic shape, edit `traffic.json` and restart the generator
with `scripts/stop-generator` and `scripts/start-generator`; no rebuild is needed.
