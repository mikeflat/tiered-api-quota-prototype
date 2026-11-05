# Quota traffic generator

Demo traffic emulator for the Tiered Tenant Quota Prototype. Today it is a skeleton: it starts
with one command, validates the gateway address, logs a startup line, stays up until it is
stopped and shuts down cleanly. It sends **no traffic yet** — tenants, pacing, weighted
selection and summaries arrive with the `traffic-generator` change.

The generator is a small flat script (`src/main.ts` plus a few flat modules), not production
code. It is deliberately **exempt from the project's Clean Architecture rule**: no layer
folders, ports or lint-enforced layering. Small pure helpers may be split into plain functions
so they can be unit-tested.

## Stack

- Node.js LTS running the TypeScript sources directly (built-in type stripping), Node `>=22.18`
- **No build step**: nothing is compiled or bundled; `tsc` only type-checks
- npm with a committed `package-lock.json`; no runtime dependencies
- Vitest (node environment) for tests, co-located as `src/*.test.ts`

| File | Purpose |
|---|---|
| `src/main.ts` | Entry point: read the setting, log, keep alive, stop on `SIGTERM` / `SIGINT` |
| `src/config.ts` | `GATEWAY_URL` → validated URL |

## Scripts

Install dependencies first:

```bash
npm ci
```

| Command | What it does |
|---|---|
| `npm start` | Run the generator (`node src/main.ts`); stop it with Ctrl+C |
| `npm run typecheck` | Type-check only (`tsc --noEmit`); also rejects syntax Node cannot strip, e.g. `enum` |
| `npm test` | Run the tests once, headless |
| `npm run test:watch` | Run the tests in watch mode |

Sources must use only erasable TypeScript syntax (no `enum`, `namespace` or parameter
properties), and relative imports use the `.ts` extension, as Node requires.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` (image: `http://gateway:8080`) | Gateway base address, absolute `http` or `https` URL |

The value is read from the environment (see `.env.example`; the file is not loaded
automatically). An invalid value stops the generator at startup with exit code `1` and a
message naming `GATEWAY_URL` and the rejected value. Example:

```bash
GATEWAY_URL=http://localhost:9090 npm start
```

prints a line like

```
2026-01-01T00:00:00.000Z generator started: gateway=http://localhost:9090, traffic=off (skeleton)
```

and on Ctrl+C `generator stopped (SIGINT)`.

## Docker

Build the image in this folder (only Docker is needed on the host):

```bash
docker build -t quota-generator:local .
```

Run it (it listens on no port and keeps running even without a gateway):

```bash
docker run -d --name quota-generator quota-generator:local
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

To point it at another gateway address, start it with `GATEWAY_URL` set (stop and remove it
the same way afterwards):

```bash
docker run -d --name quota-generator -e GATEWAY_URL=http://gateway:9090 quota-generator:local
```

The image runs `node src/main.ts` as the non-root `node` user, contains no test files and has
no healthcheck: the generator has no endpoint to probe, so "running" is its health.
