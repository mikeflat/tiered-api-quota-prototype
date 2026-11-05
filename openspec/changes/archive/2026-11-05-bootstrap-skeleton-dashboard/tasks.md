# Tasks

## 1. Scaffold the Vite React + TypeScript project

- [x] 1.1 Generate the official Vite `react-ts` template in a scratch folder and move its files into `/dashboard` (design D-1); delete `dashboard/.gitkeep` and the template's own `.gitignore`; verify `npm install` succeeds and `package-lock.json` is created
- [x] 1.2 Strip the demo content (counter, logos, `src/assets/`, the template's `public/` icons, demo CSS; remove `public/` if empty), set `<title>Quota dashboard</title>` in `index.html`, add `"engines": { "node": ">=22" }`; verify `npm run build` and `npm run lint` still pass

## 2. Clean Architecture layout and layering rules

- [x] 2.1 Create `src/domain/`, `src/application/`, `src/adapters/api/`, `src/ui/components/` each with a `.gitkeep`, and move `App.tsx` and `index.css` into `src/ui/` with `src/main.tsx` as the composition root (design D-2); verify `git status` shows the four `.gitkeep` files and `npm run build` passes
- [x] 2.2 Add the per-layer `no-restricted-imports` / `no-restricted-globals` `overrides` with explanatory messages to `.oxlintrc.json` (design D-3); verify `npm run lint` passes on the clean skeleton
- [x] 2.3 Prove the rules bite: temporarily add `src/domain/bad.ts` importing `react`, and `src/ui/components/Bad.tsx` calling `fetch`; verify `npm run lint` fails naming both violations, then delete both files and verify lint passes again

## 3. App shell and tests

- [x] 3.1 Install `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event` (versions matching the template's Vite major); add the `test` block to `vite.config.ts`, `src/test/setup.ts`, and scripts `test` / `test:watch` (design D-5); verify `npx vitest --version` runs
- [x] 3.2 Write `src/ui/App.test.tsx` first: heading "Quota dashboard" is rendered and `main` contains no digits; verify it fails against the template-leftover `App`
- [x] 3.3 Implement the shell in `src/ui/App.tsx` and plain CSS in `src/ui/index.css` (design D-4, no component library); verify `npm test` passes and `npm run lint` and `npm run build` pass

## 4. Dev server proxy

- [x] 4.1 Configure `server.port` 5173 (`strictPort`) and the proxy for `/admin` and `/actuator/health` to `GATEWAY_URL` (default `http://localhost:8080`) in `vite.config.ts`; add `.env.example` documenting `GATEWAY_URL` (design D-6); verify `npm run dev` serves `http://localhost:5173/` with `200`
- [x] 4.2 With the gateway running (`./gradlew :bootstrap:bootRun` in `/gateway`), verify `curl http://localhost:5173/actuator/health` returns the gateway's `200` `{"status":"UP"}`, and with `GATEWAY_URL=http://localhost:9090` plus a gateway started with `SERVER_PORT=9090`, verify the same through the dev server

## 5. Container image with nginx

- [x] 5.1 Write `nginx/default.conf.template` (SPA fallback, cache headers, `/admin/` and `= /actuator/health` proxied through a variable upstream with resolver and 2 s connect / 5 s read timeouts; design D-7)
- [x] 5.2 Write the two-stage `Dockerfile` (pinned Node LTS alpine build with `npm ci` + `npm run build`; pinned `nginxinc/nginx-unprivileged` alpine runtime on `8080` with `GATEWAY_UPSTREAM` / `NGINX_RESOLVER` defaults and a `wget` healthcheck on `/`) and `.dockerignore`; verify `docker build -t quota-dashboard:local .` succeeds
- [x] 5.3 Verify with no gateway: `docker run -d -p 3000:8080 quota-dashboard:local` → `GET http://localhost:3000/` is `200` HTML, `docker inspect` health becomes `healthy`, `GET /actuator/health` returns a `5xx` within a few seconds, `GET /api/v1/resource` returns the shell (not forwarded), and `docker exec <id> ps` shows nginx not running as root
- [x] 5.4 Verify with a gateway: create a user network, run the gateway image (`quota-gateway:local`) as `gateway` on it and the dashboard on the same network; `GET http://localhost:3000/actuator/health` returns `200` `{"status":"UP"}`; stop the gateway and verify the dashboard still serves `/`, start it again and verify health is forwarded again without restarting the dashboard

## 6. Documentation

- [x] 6.1 Write `dashboard/README.md`: stack, layer table and import rules (from design D-2/D-3), scripts (`dev`, `build`, `lint`, `test`, `preview`), `GATEWAY_URL`, Docker build/run commands for both standalone and networked use, `GATEWAY_UPSTREAM` / `NGINX_RESOLVER`, and the `.gitkeep` convention; verify every command in it runs as written on a clean clone (`npm ci` first)

## 7. Final check

- [x] 7.1 From a clean state (`rm -rf node_modules dist`), run `npm ci && npm run lint && npm test && npm run build` in `/dashboard` and verify all succeed; verify `openspec validate bootstrap-skeleton-dashboard --strict` passes and no file outside `/dashboard` changed besides this change's artifacts
