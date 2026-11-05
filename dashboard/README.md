# Quota dashboard

Admin dashboard for the Tiered Tenant Quota Prototype. Today it is a skeleton: an app shell
with the heading "Quota dashboard", the Clean Architecture folder layout, enforced layering,
tests, a dev server proxy and a container image. Polling, panels, the tenant grid and filters
arrive with later changes.

## Stack

- React + TypeScript (strict) + Vite, npm with a committed `package-lock.json`, Node `>=22`
- [oxlint](https://oxc.rs/docs/guide/usage/linter) (config in `.oxlintrc.json`)
- Vitest + React Testing Library in jsdom (`src/test/setup.ts`)
- Plain CSS, no component library
- Container: Node LTS build stage, unprivileged nginx runtime (`nginx/default.conf.template`)

## Source layout and layering

Dependencies point inward only. `npm run lint` fails when a rule below is broken.

| Folder | Layer | May import | Must not use |
|---|---|---|---|
| `src/domain/` | domain (pure TypeScript): models, polling state machine, status/percent/trend logic | only `domain/` | `react`, `react-dom`, `application/`, `adapters/`, `ui/`; globals `fetch`, `XMLHttpRequest`, `window`, `document`, `localStorage`, `setTimeout`, `setInterval` (timers and clocks are ports) |
| `src/application/` | use cases, hooks, ports | `domain/`, `react` | `adapters/`, `ui/`; `fetch`, `XMLHttpRequest` |
| `src/adapters/api/` | outbound HTTP adapters implementing application ports | `domain/`, `application/` | `react`, `react-dom`, `ui/` |
| `src/ui/` (`App.tsx`, `index.css`), `src/ui/components/` | presentational UI | `domain/`, `application/`, `react` | `adapters/`; `fetch`, `XMLHttpRequest` (components never call `fetch`) |
| `src/main.tsx` | composition root | everything | — |

`main.tsx` creates the adapters and hands them to the UI via props/context; UI components never
construct adapters. The rules match relative import paths at any depth (`**/adapters/**`); no
path aliases are configured, so do not add one that would bypass them.

Empty layer folders are kept in git with a `.gitkeep` file. Delete a folder's `.gitkeep` when
the first real file lands in it. Tests live next to the code as `*.test.ts(x)`.

## Scripts

Install dependencies first:

```bash
npm ci
```

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server on `http://localhost:5173` (fails if the port is taken) |
| `npm run build` | Type-check (`tsc -b`) and build static files into `dist/`; fails on type errors |
| `npm run lint` | oxlint, including the layering rules |
| `npm test` | Run the tests once, headless (Vitest + React Testing Library) |
| `npm run test:watch` | Run the tests in watch mode |
| `npm run preview` | Serve the built `dist/` locally |

## Gateway in development

The dev server forwards `/admin/**` and `/actuator/health` to the gateway, so the browser talks
to one origin (no CORS), just like in the container. Nothing else is forwarded.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` | Gateway the dev server proxies to |

`GATEWAY_URL` is read by `vite.config.ts` from the shell environment (it is not a `VITE_`
variable and never reaches the browser bundle); see `.env.example`. Example with a gateway on
port 9090:

```bash
GATEWAY_URL=http://localhost:9090 npm run dev
```

With the gateway running (`./gradlew :bootstrap:bootRun` in `/gateway`):

```bash
curl http://localhost:5173/actuator/health
```

returns the gateway's `{"status":"UP"}`. Without a gateway it returns `502`.

## Docker

Build the image in this folder (only Docker is needed on the host):

```bash
docker build -t quota-dashboard:local .
```

The container serves the app on port `8080` as the non-root `nginx` user and has a healthcheck
on `GET /` that does not depend on the gateway. nginx forwards `/admin/**` and
`/actuator/health` to the gateway with path, query, method and body unchanged (2 s connect /
5 s read timeouts); every other path, including `/api/**` and other `/actuator/*` paths, gets
the app shell. The gateway address is resolved per request, so the dashboard starts without a
gateway and picks it up when it appears.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_UPSTREAM` | `gateway:8080` | Gateway `host:port` that nginx proxies to |
| `NGINX_RESOLVER` | `127.0.0.11` | DNS server nginx uses to resolve the gateway host (Docker's embedded DNS on user-defined networks) |

### Standalone

```bash
docker run -d --name quota-dashboard -p 3000:8080 quota-dashboard:local
```

`http://localhost:3000/` shows the app shell and the container becomes `healthy`. On the
default bridge network there is no gateway to reach, so `/actuator/health` and `/admin/**`
answer `502` after about 2 s. Stop it with:

```bash
docker rm -f quota-dashboard
```

### Networked with the gateway

Put both containers on one user-defined network, with the gateway named `gateway` (build
`quota-gateway:local` first, see `/gateway/README.md`):

```bash
docker network create quota-net
```

```bash
docker run -d --name gateway --network quota-net quota-gateway:local
```

```bash
docker run -d --name quota-dashboard --network quota-net -p 3000:8080 quota-dashboard:local
```

```bash
curl http://localhost:3000/actuator/health
```

returns `{"status":"UP"}` once the gateway has started. Stopping and starting the gateway does
not require restarting the dashboard. A gateway with another name or port is set with
`-e GATEWAY_UPSTREAM=<host>:<port>`. Clean up with:

```bash
docker rm -f quota-dashboard gateway
```

```bash
docker network rm quota-net
```
