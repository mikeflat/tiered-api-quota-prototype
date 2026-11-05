# Design

## Context

`/dashboard` contains only `.gitkeep`. The gateway slice (`bootstrap-skeleton-gateway`) set the conventions this slice mirrors: Clean Architecture layers with empty directories kept by `.gitkeep` (no marker code), an automated rule that enforces the layering, a two-stage Dockerfile with a non-root runtime and a healthcheck, and a README with exact run commands. The root `.gitignore` already ignores `node_modules/`, `dist/`, `coverage/`, `*.tsbuildinfo`, `.env` / `.env.*` (except `.env.example`) and `*.local`. Local toolchain: Node 25 / npm 11, Docker 29. Requirements for the result are in `specs/dashboard-runtime/spec.md`; motivation and scope in proposal.md.

## Goals / Non-Goals

**Goals:**
- `npm ci && npm run lint && npm test && npm run build` passes in `/dashboard` on a clean checkout, with no Docker and no gateway.
- `docker build` + `docker run` in `/dashboard` serves the shell and proxies the admin/health paths to a gateway when one is reachable.
- Folder layout and lint rules ready so items 12–15 drop files into the right layer without restructuring.

**Non-Goals:**
- No placeholder domain types, ports, hooks or HTTP client: `usePolling`, the snapshot/health clients and their tests arrive with `dashboard-polling-and-states`.
- No compose service, no `/scripts` entries (another `bootstrap-skeleton` slice).

## Decisions

### D-1. Scaffold from the official Vite `react-ts` template, then strip it

Run `npm create vite@latest dashboard -- --template react-ts` into a temp location and move its files into `/dashboard` (the folder already exists). Keep: `package.json` scripts `dev`, `build` (`tsc -b && vite build`), `lint`, `preview`; the split `tsconfig.json` / `tsconfig.app.json` / `tsconfig.node.json` with `strict` on; `.oxlintrc.json` (oxlint with the `react`, `typescript`, `oxc` plugins, incl. `react/rules-of-hooks` and `react/only-export-components`; create-vite 9 replaced ESLint with oxlint in its templates). Remove: `src/assets/`, the template's `public/` icons (`favicon.svg`, `icons.svg`), the counter demo, `App.css` demo styles. Package manager npm with a committed `package-lock.json` (no extra toolchain; matches the generator, D12). Dependency versions are whatever the template pins at implementation time, then locked; `package.json` gets `"engines": { "node": ">=22" }` (current LTS lines and the local Node 25 both satisfy it).

*Alternative:* hand-write the config — rejected: more to maintain, and the template is the reference setup for React + Vite + TS.

### D-2. Layer folders under `src/`, kept with `.gitkeep`

| Folder | Clean Architecture layer | May import | Later content (architecture §2.3, backlog 12–15) |
|---|---|---|---|
| `src/domain/` | domain (pure TS) | nothing outside `domain/` | snapshot/health models, polling state machine (consecutive failures → unreachable → reconnected), status/percent/countdown logic, trend math |
| `src/application/` | application: use cases, hooks, ports | `domain/`, `react` | ports `SnapshotSource`, `HealthSource`, `TierChanger`, `Clock`/timer; `usePolling`, `useDashboard`, tier-change use case |
| `src/adapters/api/` | outbound adapter (HTTP) | `domain/`, `application/` (ports) | `fetch` clients for `/admin/snapshot`, `/actuator/health`, `PUT /admin/tenants/{id}/tier`, `/admin/tiers` with 2 s timeout |
| `src/ui/` (`App.tsx`, `App.css`, `index.css`) and `src/ui/components/` | UI (presentational) | `domain/`, `application/`, `react` | header, banners, traffic panels, SVG sparkline, tenant grid, filters |
| `src/main.tsx` | composition root (frameworks/infrastructure) | everything | creates adapters, injects them into the app via props/context, mounts React |

`.gitkeep` goes into `src/domain/`, `src/application/`, `src/adapters/api/`, `src/ui/components/` (the four folders that start empty); each is deleted when its folder gets its first file. `src/ui/` itself holds `App.tsx`, so it needs none. `src/test/setup.ts` holds test setup (outside the layers). `main.tsx` stays at the `src/` root where Vite and its `index.html` expect it.

UI components receive data and callbacks via props and hooks; they never construct adapters — `main.tsx` wires them. This mirrors the gateway's `bootstrap` module.

*Alternative:* feature folders (`features/tenants/...`) — rejected: the project context prescribes layer separation for the dashboard, and the app is small (one screen).

### D-3. Layering enforced by oxlint built-in rules, no extra plugin

Add per-folder `overrides` entries to `.oxlintrc.json` (oxlint implements ESLint's `no-restricted-imports` / `no-restricted-globals`, including `patterns` groups with messages; verified against oxlint 1.91):
- `src/domain/**`: `no-restricted-imports` with patterns `**/application/**`, `**/adapters/**`, `**/ui/**`, `react`, `react-dom`, `react/*`, `react-dom/*`; `no-restricted-globals` for `fetch`, `XMLHttpRequest`, `window`, `document`, `localStorage`, `setTimeout`, `setInterval` (timers and clocks are ports, per project context).
- `src/application/**`: `no-restricted-imports` patterns `**/adapters/**`, `**/ui/**`; `no-restricted-globals` `fetch`, `XMLHttpRequest`.
- `src/ui/**`: `no-restricted-imports` pattern `**/adapters/**`; `no-restricted-globals` `fetch`, `XMLHttpRequest`.
- `src/adapters/**`: `no-restricted-imports` patterns `**/ui/**`, `react`, `react-dom`.

Each restriction carries a `message` naming the rule ("domain must not depend on React"), which satisfies the spec's "naming the forbidden import". Patterns match relative specifiers (`../application/x`), so no path alias is needed. A Vitest test cannot run the linter cheaply on purpose-made bad files, so the "rejected" scenarios are verified once during implementation by temporarily adding a violating file and running `npm run lint` (task 2.3), mirroring how the gateway's ArchUnit rules are tolerated while empty.

*Alternatives:* switching to ESLint (the pre-v9 template) — rejected: more dependencies for the same built-in rules; `eslint-plugin-boundaries` / `import-x/no-restricted-paths` — rejected: extra dependency for four simple rules; `dependency-cruiser` — rejected for the same reason.

### D-4. App shell

`src/ui/App.tsx` renders a `<header>` with `<h1>Quota dashboard</h1>` and a `<main>` with one short muted line, "No data yet." (not "Waiting for the gateway…", because nothing polls yet). Plain CSS in `src/ui/index.css` (system font stack, readable spacing), no component library (FR-22). `index.html` title "Quota dashboard". No metric numbers (spec: no zeros pretending to be data).

### D-5. Tests: Vitest + React Testing Library in jsdom

`vitest` configured inside `vite.config.ts` (`test: { environment: 'jsdom', setupFiles: './src/test/setup.ts', globals: false, css: false }`), with `/// <reference types="vitest/config" />`. `setup.ts` imports `@testing-library/jest-dom/vitest` and calls `cleanup` after each test. Scripts: `"test": "vitest run"`, `"test:watch": "vitest"`. Tests import `describe`/`it`/`expect` from `vitest` explicitly (no globals); jest-dom matcher types come from the setup import, which `tsconfig.app.json` includes. First test `src/ui/App.test.tsx`: renders `App`, asserts the heading via `getByRole('heading', { name: 'Quota dashboard' })` and that no digits are rendered in `main`. Co-located `*.test.ts(x)` files is the convention for later changes (domain tests run without jsdom-specific APIs). `@testing-library/user-event` is installed now because items 14–15 need it.

### D-6. Dev server proxy

`vite.config.ts`: `server: { port: 5173, strictPort: true, proxy: { '/admin': target, '/actuator/health': target } }` where `target = process.env.GATEWAY_URL ?? 'http://localhost:8080'` (read in the Node-side config, so it is not a `VITE_` variable and never reaches the browser bundle). `.env.example` documents `GATEWAY_URL`. Only `/actuator/health` is proxied, not all of `/actuator` (spec: other gateway paths are not forwarded).

### D-7. Container: Node LTS build stage, unprivileged nginx runtime

`Dockerfile`:
- Build stage `node:<active LTS major>-alpine` (pinned major, chosen at implementation time from `node:lts-alpine`): copy `package.json` + `package-lock.json`, `npm ci` with a BuildKit npm cache mount, copy sources, `npm run build`. Lint and tests are not run in the image build (they run in the dev loop / later CI; keeps the image build fast).
- Runtime stage `nginxinc/nginx-unprivileged:<stable>-alpine` (pinned): non-root `nginx` user, listens on `8080`; copy `dist/` to `/usr/share/nginx/html` and `nginx/default.conf.template` to `/etc/nginx/templates/`; `ENV GATEWAY_UPSTREAM=gateway:8080 NGINX_RESOLVER=127.0.0.11`; `EXPOSE 8080`; `HEALTHCHECK` with busybox `wget -q -O /dev/null http://127.0.0.1:8080/` (static, so health never depends on the gateway).

`nginx/default.conf.template` (the image's entrypoint runs `envsubst` on templates at start):
- `location / { try_files $uri /index.html; }`; long cache for `/assets/` (hashed names), `no-cache` for `index.html`.
- `location /admin/` and `location = /actuator/health`: `resolver ${NGINX_RESOLVER} valid=10s; set $gateway http://${GATEWAY_UPSTREAM}; proxy_pass $gateway;` with `proxy_connect_timeout 2s`, `proxy_read_timeout 5s`, `proxy_http_version 1.1`, forwarded headers. Using a variable makes nginx resolve the gateway per request, so the container starts and serves `/` when the `gateway` host does not exist yet (spec: "Gateway is down") and recovers when it comes back. With a variable in `proxy_pass` and no URI part, the original request URI (path + query) is passed unchanged.
- Everything else, including `/api/**` and other `/actuator/*` paths, falls to `location /` and gets the SPA shell (not forwarded).

`127.0.0.11` is Docker's embedded DNS on user-defined networks (compose). For a plain `docker run` on the default bridge, the README shows `-e GATEWAY_UPSTREAM=host.docker.internal:8080 -e NGINX_RESOLVER=<host resolver>` or simply notes that proxied paths answer `502` there; the shell and healthcheck still work.

`.dockerignore`: `node_modules`, `dist`, `coverage`, `.env*` (except example), `*.log`.

*Alternative:* `nginx:alpine` running as root on port 80 — rejected: the gateway runtime is non-root and the spec asks the same; static `proxy_pass http://gateway:8080` — rejected: nginx refuses to start when the host can't be resolved, which breaks "start the dashboard alone" (FR-28).

### D-8. Files

- Added: `package.json`, `package-lock.json`, `index.html`, `tsconfig*.json`, `vite.config.ts`, `.oxlintrc.json`, `src/main.tsx`, `src/ui/App.tsx`, `src/ui/App.test.tsx`, `src/ui/index.css`, `src/test/setup.ts`, the four `.gitkeep` files (D-2), `nginx/default.conf.template`, `Dockerfile`, `.dockerignore`, `.env.example`, `README.md`.
- Removed: `dashboard/.gitkeep`, template demo assets, and the template's `public/` folder once empty (no `.gitkeep`: nothing is planned there).
- The template's own `.gitignore` is dropped: the root `.gitignore` already covers it.

## Risks / Trade-offs

- [`npm create vite@latest` pulls whatever is newest, possibly a major with breaking config changes] → versions are locked by `package-lock.json` on first install; tasks verify lint/test/build right after scaffolding.
- [jsdom / Vitest / Vite major mismatch] → install Vitest matching the template's Vite major; `npm test` in task 3 catches it.
- [Lint glob restrictions can be bypassed by an alias or deep relative path] → no path aliases are configured; patterns use `**/` so any depth matches. Documented in README.
- [Resolver `127.0.0.11` absent outside a Docker user network] → configurable via `NGINX_RESOLVER`; shell and healthcheck unaffected; README explains.
- [Healthcheck on `/` does not reveal gateway problems] → intended: the dashboard's own health must not depend on the gateway; gateway state is shown in the UI (FR-21, item 12).
- [Docker scenarios verified manually, not automated] → README contains the exact commands; the compose slice exercises them again (same trade-off as the gateway slice).
- [Windows checkout with CRLF] → no shell scripts are executed inside the image; nginx tolerates CRLF in config. A `dashboard/.gitattributes` is not needed.

## Migration Plan

Not applicable: `/dashboard` is empty and nothing depends on it yet. Rollback = restore `dashboard/.gitkeep` and delete the rest.
