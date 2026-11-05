# Design

## Context

`/generator` contains only `.gitkeep`. The two earlier slices set the operational conventions this one keeps: a Dockerfile with a non-root runtime, `.env.example`, and a README with exact commands. The dashboard pins Node `26-alpine` in its build stage and uses TypeScript `~7.0` and Vitest `^4.1`. The root `.gitignore` already ignores `node_modules/`, `coverage/`, `*.tsbuildinfo`, `.env*` (except `.env.example`). Local toolchain: Node 25 / npm 11, Docker 29. Motivation and scope: proposal.md. Required behaviour: `specs/generator-runtime/spec.md`.

Unlike the gateway and dashboard, the generator is a demo traffic emulator of a few lines, so it is **exempt from the project's Clean Architecture rule** (see D-2).

## Goals / Non-Goals

**Goals:**
- `npm ci && npm run typecheck && npm test` passes in `/generator` on a clean checkout, without Docker or a gateway; `npm start` runs `src/main.ts` directly.
- `docker build` + `docker run` / `docker stop` behave as the spec says.

**Non-Goals:**
- No layer folders, ports, adapters or linter.
- No traffic code yet: sending, pacing and summaries arrive with `traffic-generator`.
- No compose service, no `/scripts` entries (another `bootstrap-skeleton` slice).

## Decisions

### D-1. Plain npm project, TypeScript run by Node's type stripping

`package.json`: `"name": "quota-generator"`, `"private": true`, `"type": "module"`, `"engines": { "node": ">=22.18" }` (first 22.x line with type stripping on by default; the local Node 25 and the image's Node 26 satisfy it). Scripts:

| Script | Command | Purpose |
|---|---|---|
| `start` | `node src/main.ts` | run the generator (no build) |
| `typecheck` | `tsc --noEmit` | type-check only; never emits |
| `test` / `test:watch` | `vitest run` / `vitest` | tests |

No runtime `dependencies`. `devDependencies`: `typescript`, `@types/node`, `vitest`, at the same majors as the dashboard, locked in a committed `package-lock.json`.

`tsconfig.json`: `strict`, `target`/`lib` `ES2024`, `module`/`moduleResolution` `nodenext`, `types: ["node"]`, `noEmit`, `allowImportingTsExtensions`, `verbatimModuleSyntax`, `erasableSyntaxOnly` (rejects `enum`, `namespace`, parameter properties — syntax Node cannot strip; spec "Sources stay runnable without compilation"), `include: ["src", "vitest.config.ts"]`. Relative imports use the `.ts` extension, as Node requires.

*Alternatives:* `tsx` / `ts-node` — rejected: an extra runtime dependency for what Node does natively (D12 "no build step, no new toolchain"); compiling with `tsc` to `dist/` — rejected by D12.

### D-2. Flat layout, no Clean Architecture

```
generator/src/
  main.ts          entry point: read config, log, keep alive, handle signals
  config.ts        GATEWAY_URL → validated URL
  config.test.ts
  main.test.ts
```

Item 11 adds traffic code to these files or a few more flat modules next to them (e.g. `traffic.ts`); small pure helpers (weighted pick, pacing) may be split into functions so they can be unit-tested, but no layers, ports or dependency rules are introduced.

*Alternative:* the project-wide Clean Architecture layout (domain / application / adapters / composition root with lint-enforced layering) — rejected for the generator: it is a demo emulator, not production code, and the layering would outweigh the few lines of logic. This is a deliberate exception to the project context; the gateway and dashboard keep the rule.

### D-3. Gateway address (`src/config.ts`)

`readGatewayUrl(env: Readonly<Record<string, string | undefined>>): URL` — takes the environment as a parameter so tests don't touch `process.env`. Unset or blank → `http://localhost:8080`. Otherwise `new URL(value)`; a parse error or a protocol other than `http:`/`https:` throws an `Error` with the message `GATEWAY_URL must be an absolute http(s) URL, got "<value>"`. The startup line prints the URL without a trailing `/` when the path is empty, e.g. `http://gateway:9090`.

### D-4. Entry point and lifecycle (`src/main.ts`)

1. `readGatewayUrl(process.env)`; on error write the message to stderr and set `process.exitCode = 1`, return (no `process.exit()` mid-flight, so stderr is flushed).
2. Log `generator started: gateway=<url>, traffic=off (skeleton)` to stdout with an ISO timestamp prefix, via `console`.
3. Keep the event loop alive with a long `setInterval` no-op handle (nothing else holds the loop open yet).
4. `process.once('SIGTERM' | 'SIGINT', …)`: clear the handle, log `generator stopped (<signal>)`, `process.exitCode = 0`; the loop drains and Node exits.

Node runs as PID 1 in the container; because the handlers are installed, `SIGTERM` from `docker stop` is honoured (PID 1 otherwise ignores it), so no `tini`/`--init` is needed.

### D-5. Tests: Vitest in the node environment

`vitest.config.ts`: `test: { environment: 'node', include: ['src/**/*.test.ts'] }`.
- `src/config.test.ts`: unset → default; blank → default; `http://gateway:9090` → that URL; `https://…` accepted; `not a url` and `ftp://gateway` → error whose message names `GATEWAY_URL` and the value.
- `src/main.test.ts` (process smoke test, spawns `process.execPath src/main.ts` with a controlled env): startup line on stdout contains `http://localhost:8080` and `traffic=off`; with `GATEWAY_URL=ftp://gateway` the process exits non-zero within 5 s with the message on stderr. The child is always killed in `afterEach`. Signal handling is checked manually (Ctrl+C locally, `docker stop` in the container) rather than in Vitest, because Windows `kill()` terminates without running handlers.

### D-6. Container image

`Dockerfile` (single stage — nothing to build):
- `FROM node:26-alpine` (same pinned major as the dashboard build stage), `WORKDIR /app`.
- `COPY package.json ./` (needed for `"type": "module"`) and `COPY src ./src`; test files excluded by `.dockerignore` (`**/*.test.ts`, `node_modules`, `coverage`, `.env*` except example, `*.log`). No `npm ci`: there are no runtime dependencies. When item 11 adds one, the Dockerfile gains `npm ci --omit=dev` with the lockfile.
- `ENV NODE_ENV=production GATEWAY_URL=http://gateway:8080`; `USER node` (uid 1000, from the base image); no `EXPOSE`, no `HEALTHCHECK` (nothing to probe; "running" is the health); `CMD ["node", "src/main.ts"]` (exec form, so Node receives signals directly).

### D-7. Files

- Added under `/generator`: `package.json`, `package-lock.json`, `tsconfig.json`, `vitest.config.ts`, `src/main.ts`, `src/main.test.ts`, `src/config.ts`, `src/config.test.ts`, `Dockerfile`, `.dockerignore`, `.env.example` (`GATEWAY_URL=`), `README.md`.
- Removed: `generator/.gitkeep`.

## Risks / Trade-offs

- [Project context still prescribes Clean Architecture for the generator, so later changes (item 11) may be planned with layers again] → this design records the exception; `traffic-generator` should keep the flat layout.
- [TypeScript 7 (native compiler) option support differs from 5.x] → the options used are long-standing plus `erasableSyntaxOnly` (5.8+); task 1.2 verifies `tsc --noEmit` rejects an `enum`. If TS 7 lacks one, fall back to the dashboard's exact TypeScript version and note it.
- [Node type stripping changes between majors] → `engines` and the pinned image major bound it; only erasable syntax is used.
- [Process smoke test is timing-sensitive] → waits on the startup line with a 5 s timeout instead of sleeping; child killed in `afterEach`.
- [Signal handling not covered by automated tests] → verified manually locally and with `docker stop` (tasks 3.2, 4.2).
- [No healthcheck] → compose will not gate anything on the generator; its state is visible in `docker ps` and its logs.

## Migration Plan

Not applicable: `/generator` is empty and nothing depends on it yet. Rollback = restore `generator/.gitkeep` and delete the rest.
