# Tasks

## 1. Project setup

- [x] 1.1 Delete `generator/.gitkeep`; create `package.json` (name, `"type": "module"`, `engines` `>=22.18`, scripts `start`/`typecheck`/`test`/`test:watch`, no runtime deps) and install dev dependencies `typescript`, `@types/node`, `vitest` at the dashboard's majors (design D-1); verify `npm install` succeeds and `package-lock.json` is created
- [x] 1.2 Add `tsconfig.json` (strict, `nodenext`, `noEmit`, `allowImportingTsExtensions`, `verbatimModuleSyntax`, `erasableSyntaxOnly`) and `vitest.config.ts` (node environment, `src/**/*.test.ts`); verify a temporary `src/bad.ts` with an `enum` makes `npm run typecheck` fail; delete it

## 2. Gateway address setting

- [x] 2.1 Write `src/config.test.ts` first (unset, blank, `http://gateway:9090`, `https://…`, `not a url`, `ftp://gateway`; error message names `GATEWAY_URL` and the value); verify it fails because the module is missing
- [x] 2.2 Implement `readGatewayUrl` in `src/config.ts` (design D-3); verify `npm test` and `npm run typecheck` pass

## 3. Entry point and lifecycle

- [x] 3.1 Write `src/main.test.ts` first: startup line contains `http://localhost:8080` and `traffic=off`; `GATEWAY_URL=ftp://gateway` exits non-zero within 5 s with the message on stderr (design D-5); verify it fails because `main.ts` is missing
- [x] 3.2 Implement `src/main.ts` (read setting, startup line, keep-alive handle, `SIGTERM`/`SIGINT` handlers, exit codes; design D-4); verify `npm test` and `npm run typecheck` pass, `npm start` prints the startup line and Ctrl+C prints the stop line, and `GATEWAY_URL=http://gateway:9090 npm start` shows that address
- [x] 3.3 Verify no output files are produced: after `npm start` and `npm test`, `git status --ignored generator` shows nothing besides `node_modules/` (no `dist/`, no `.js` files)

## 4. Container image

- [x] 4.1 Write the `Dockerfile` (pinned `node:26-alpine`, copy `package.json` + `src`, `NODE_ENV` and `GATEWAY_URL=http://gateway:8080`, `USER node`, exec-form `CMD`, no `EXPOSE`/`HEALTHCHECK`) and `.dockerignore` excluding tests (design D-6); verify `docker build -t quota-generator:local .` succeeds and the image contains no `*.test.ts`
- [x] 4.2 Verify with no gateway: `docker run -d --name gen quota-generator:local` stays running, `docker logs gen` shows the startup line with `http://gateway:8080`, `docker exec gen ps` shows node not running as root; `docker stop gen` returns within a few seconds and `docker inspect gen` reports exit code `0` with the stop line as the last log line
- [x] 4.3 Verify configuration and fail-fast in the container: `-e GATEWAY_URL=http://gateway:9090` shows that address; `-e GATEWAY_URL=ftp://gateway` exits with a non-zero code and the message in `docker logs`

## 5. Documentation

- [x] 5.1 Write `generator/README.md` (purpose and current skeleton state, note that it is a flat demo script exempt from the project's Clean Architecture rule, scripts, `GATEWAY_URL`, Docker build/run/stop commands) and `.env.example`; verify every command in it runs as written on a clean clone (`npm ci` first)

## 6. Final check

- [x] 6.1 From a clean state (`rm -rf node_modules`), run `npm ci && npm run typecheck && npm test` in `/generator` and verify all succeed; verify `openspec validate bootstrap-skeleton-generator --strict` passes and no file outside `/generator` changed besides this change's artifacts
