# Tasks

No gateway or dashboard code changes, so there are no domain/use-case/adapter layers here; tasks go from the compose definition (inside) to the scripts that wrap it (outside). There is no automated test framework for compose and shell scripts in this project; each task names the command or observable behavior that verifies it.

## 1. Repository setup

- [x] 1.1 Add root `.gitattributes` with `*.sh text eol=lf`, `*.cmd text eol=crlf`, `*.bat text eol=crlf` (D-S8); verify with `git check-attr eol -- scripts/x.sh scripts/x.cmd` reporting `lf` / `crlf`
- [x] 1.2 Delete `scripts/.gitkeep` and create `scripts/lib/`; verify `git status` shows the removal

## 2. Compose definition

- [x] 2.1 Create `docker-compose.yml` with `name: tiered-quota` and the `redis` service: pinned `redis:8.<minor>-alpine`, `redis-server --save "" --appendonly no`, `redis-cli ping` healthcheck, port `127.0.0.1:${REDIS_PORT:-6379}:6379`, `restart: "no"`, no volume (D-S1, D-S4, D-S5); verify `docker compose up -d --wait redis` becomes healthy and `docker compose exec redis redis-cli ping` prints `PONG`
- [x] 2.2 Add the `gateway` service: build `./gateway`, image `quota-gateway:local`, `SPRING_DATA_REDIS_HOST=redis`, `SPRING_DATA_REDIS_PORT=6379`, port `127.0.0.1:${GATEWAY_PORT:-8080}:8080`, `depends_on: redis` with `condition: service_started, required: false` (D-S2, D-S3); verify `docker compose up -d --build --wait gateway` becomes healthy and `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}`, also with Redis stopped
- [x] 2.3 Add the `dashboard` service: build `./dashboard`, image `quota-dashboard:local`, port `127.0.0.1:${DASHBOARD_PORT:-3000}:8080`, `depends_on: gateway` (started, not required); verify `curl http://localhost:3000/` returns the app shell and `curl http://localhost:3000/actuator/health` returns `{"status":"UP"}`
- [x] 2.4 Add the `generator` service: build `./generator`, image `quota-generator:local`, no ports, `depends_on: gateway` (started, not required); verify `docker compose logs generator` shows the startup line naming `http://gateway:8080`
- [x] 2.5 Add root `.env.example` documenting `GATEWAY_PORT`, `DASHBOARD_PORT`, `REDIS_PORT` with their defaults; verify `docker compose config -q` passes and, with `GATEWAY_PORT=9090` in the environment, `docker compose config` shows `127.0.0.1:9090` for the gateway and no `8080` host port

## 3. Shared script helpers

- [x] 3.1 Write `scripts/lib/compose.sh` (POSIX sh): resolve repo root from the calling script, check `docker`, `docker compose version` and `docker info`, print `error: Docker is not available: ...` to stderr and exit `1` on failure, else run `docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" "$@"` and return its exit code (D-S7); verify with `sh -n` and, if available, `shellcheck -s sh` reporting no errors
- [x] 3.2 Write `scripts/lib/compose.cmd` with the same steps and messages in batch (`%~dp0..` root, `where docker`, `docker compose version`, `docker info`, `exit /b %ERRORLEVEL%`); verify `cmd /c scripts\lib\compose.cmd ps` lists the stack from a different current directory

## 4. Whole-stack scripts

- [x] 4.1 Write `scripts/start.sh` and `scripts/start.cmd`: `up -d --build --wait --wait-timeout 180 --remove-orphans`, then print the dashboard and gateway URLs from `DASHBOARD_PORT` / `GATEWAY_PORT` (defaults `3000` / `8080`); verify on a clean state both forms exit `0`, four services are running/healthy (`docker compose ps`), and the output names `http://localhost:3000` and `http://localhost:8080`
- [x] 4.2 Write `scripts/stop.sh` and `scripts/stop.cmd`: `down --remove-orphans`; verify both forms exit `0` with the stack running and again with nothing running, and `docker compose ps -a` is empty afterwards while `quota-*:local` images remain
- [x] 4.3 Verify start edge cases with both forms: running `start` twice exits `0`; after editing a generator source file `start` rebuilds and recreates only the generator; with the gateway host port taken by another process `start` exits non-zero naming the port

## 5. Component scripts

- [x] 5.1 Write `start-redis` / `stop-redis` in `.sh` and `.cmd` (`up -d --no-deps --wait --wait-timeout 180 redis` / `stop redis`, extra args forwarded); verify with the stack running: `stop-redis` leaves gateway, dashboard and generator running and `curl http://localhost:8080/actuator/health` still returns `UP`; `start-redis` brings Redis back healthy without restarting other containers (check `docker compose ps` start times)
- [x] 5.2 Write `start-gateway` / `stop-gateway` in `.sh` and `.cmd`; verify `stop-gateway` leaves the dashboard serving `/` with `200` while `/actuator/health` on port `3000` returns `5xx` within a few seconds; `start-gateway` returns once the gateway is healthy and `/actuator/health` on port `3000` is `200` again without restarting the dashboard; from an empty state `start-gateway` starts only the gateway
- [x] 5.3 Write `start-generator` / `stop-generator` in `.sh` and `.cmd`; verify `stop-generator` exits `0`, the generator container's exit code is `0` and its log ends with the stop line; `start-generator` brings it back running; `stop-generator` with the generator already stopped exits `0`
- [x] 5.4 Mark all `.sh` files executable in git (`git update-index --chmod=+x scripts/*.sh scripts/lib/*.sh`); verify `git ls-files -s scripts` shows mode `100755` for every `.sh`

## 6. Documentation

- [x] 6.1 Write `scripts/README.md`: prerequisites (Docker with compose v2 ≥ 2.21, `sh` or `cmd`), the script table, port variables and `.env`, and the Redis-outage and gateway-outage steps; verify each documented command runs as written in both forms
- [x] 6.2 Align the docs with the dashboard deviation: FR-28 in `docs/02 requirements.md` names Redis, gateway and generator for component scripts (dashboard only with the whole stack), and the `/scripts` line in `docs/03 architecture.md` §10 lists `redis, gateway, generator`; verify `grep -n "FR-28" "docs/02 requirements.md"` shows the new wording

## 7. Integration checks

- [x] 7.1 Check the script sets match: `/scripts` contains exactly one `.sh` and one `.cmd` for each of `start`, `stop`, `start-redis`, `stop-redis`, `start-gateway`, `stop-gateway`, `start-generator`, `stop-generator`, and nothing else besides `README.md` and `lib/`
- [x] 7.2 Run `sh scripts/start.sh` from the home directory (outside the repo) in Git Bash and `scripts\start.cmd` from `C:\` in `cmd.exe`; verify both act on the repository stack
- [x] 7.3 With Docker Desktop's engine stopped, run one `.sh` and one `.cmd` script; verify each exits non-zero with the "Docker is not available" message
- [x] 7.4 Introduce a compile error in a gateway source file, run `start`; verify non-zero exit with the build error shown; revert the error
- [x] 7.5 Confirm published ports are loopback-only: `docker compose ps` / `docker port` show `127.0.0.1:` for Redis, gateway and dashboard; finish with `stop` leaving no stack containers
