# Proposal

## Why

Backlog item 1, `bootstrap-skeleton` (docs/04 backlog.md), asks for an empty gateway, dashboard and generator that each start and answer a trivial request. The gateway slice is done (`bootstrap-skeleton-gateway`, archived); this change is the **dashboard slice**. `/dashboard` holds only a `.gitkeep` today, and backlog items 12–15 (polling, panels, grid, filters) need a buildable, tested, containerised React + TypeScript + Vite project with the Clean Architecture folder layout already in place, so they only add code, not structure.

## What Changes

- Initialise `/dashboard` as an empty React + TypeScript + Vite project (npm, committed `package-lock.json`), starting from the official Vite `react-ts` template and stripped of its demo content (counter, logos, demo CSS).
- Create the Clean Architecture folder layout under `dashboard/src/`: `domain/`, `application/`, `adapters/api/`, `ui/components/`, with `src/main.tsx` as the composition root. Empty folders are kept in git with `.gitkeep` files (the request's ".keepgit" is read as the project's existing `.gitkeep` convention; no placeholder modules).
- Enforce the inward-only dependency rule with the template's linter, oxlint (built-in `no-restricted-imports` / `no-restricted-globals` per layer): domain imports nothing from outer layers, React or `fetch`; application imports no adapters or UI; UI components never call `fetch` (project context: "components never call fetch directly").
- Render a minimal app shell (`App` in `ui/`): page title "Quota dashboard" and a neutral placeholder line, plain CSS, no component library (FR-22).
- Set up Vitest + React Testing Library (jsdom) with one smoke test for the shell (NFR-9 groundwork).
- Configure the Vite dev server to proxy `/admin` and `/actuator` to the gateway (default `http://localhost:8080`, overridable by an environment variable), so local development has the same single-origin shape as production (D13).
- Add a two-stage `Dockerfile` (Node LTS build → unprivileged nginx runtime) and an nginx config that serves the built files and reverse-proxies `/admin/**` and `/actuator/health` to a configurable gateway upstream (D13, architecture §2.3). The container answers `GET /` with the app shell even when the gateway is not running.
- Add `dashboard/README.md`, `.env.example`, `.dockerignore`; delete the stray `dashboard/.gitkeep`.

## Capabilities

### New Capabilities
- `dashboard-runtime`: how the admin dashboard is built, started and served — dev server, production build, container image with nginx serving static files and proxying the admin and health paths to the gateway, and the enforced source layering. Later dashboard changes (items 12–15) add separate behaviour capabilities on top of it.

### Modified Capabilities
<!-- None: gateway-runtime requirements are unchanged. -->

## Impact

- **Code**: everything under `/dashboard`. No other folder changes (root `.gitignore` already covers `node_modules/`, `dist/`, `coverage/`, `.env`).
- **Requirements covered**: the dashboard part of backlog item 1 ("each service starts and answers a trivial request"); FR-22 groundwork (no component library); NFR-9 groundwork (Vitest + RTL wired with a first test); D13 (nginx serves the dashboard and proxies the admin API — one origin, no CORS); architecture §2.3 and §10 layout. FR-19..FR-21 are not implemented here.
- **Dependencies (new, dashboard only)**: React, React DOM, Vite + `@vitejs/plugin-react`, TypeScript, oxlint (template config), Vitest, `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event`, jsdom. Runtime images: official Node LTS (build) and `nginxinc/nginx-unprivileged` (serve).
- **Ports**: Vite dev server `5173`; container listens on `8080` (unprivileged nginx), published locally on `3000` in the README examples to avoid clashing with the gateway.

## Out of scope

- Root `docker-compose.yml`, the Redis service and `/scripts` start/stop scripts (FR-27, FR-28) — another `bootstrap-skeleton` slice.
- Generator skeleton.
- Polling, `usePolling`, snapshot/health clients, banners, panels, grid, filters, tier changes (FR-19..FR-21, backlog items 12–15).
- Everything in docs/02 requirements.md section 6: no authentication for the dashboard, no push delivery (SSE/WebSocket), no component library or design system, no visual design work, no controlling the generator from the dashboard.
- Routing, state-management libraries and a CSS framework.
