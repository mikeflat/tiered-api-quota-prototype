# Scripts

Start and stop the local stack (`docker-compose.yml` at the repository root): Redis, the gateway, the dashboard and the generator. Every script is a thin wrapper around one `docker compose` command and comes in two forms with the same name and behavior:

- `scripts/<name>.sh` for a POSIX shell (Linux, macOS, Git Bash or WSL on Windows)
- `scripts\<name>.cmd` for Windows `cmd.exe` (also runs from PowerShell or Explorer)

Scripts find the repository from their own location, so they work from any current directory. They exit non-zero with a message when Docker is not available or the compose command fails.

## Prerequisites

- Docker with the compose v2 plugin (`docker compose`), version 2.21 or newer, and the engine running
- `sh` for the `.sh` scripts, or `cmd.exe` for the `.cmd` scripts

## Scripts

| Script | What it does |
|---|---|
| `start` | Builds all images, starts the whole stack, waits until every service is up (healthy where it has a healthcheck) and prints the dashboard and gateway URLs. Safe to run again: it rebuilds changed sources and recreates only the services whose image changed. |
| `stop` | Stops and removes all stack containers and the network. Built images are kept. |
| `start-redis` / `stop-redis` | Starts or stops only Redis. |
| `start-gateway` / `stop-gateway` | Starts or stops only the gateway. |
| `start-generator` / `stop-generator` | Starts or stops only the traffic generator. |

The dashboard has no component scripts; it starts and stops with the whole stack.

Component scripts never touch other services. `start-<component>` returns once the service is up and does **not** rebuild an existing image, so it takes seconds; run `start` to pick up source changes, or pass `--build` (extra arguments go to `docker compose`, e.g. `scripts/start-gateway.sh --build`). `stop-<component>` keeps the container, so the next `start-<component>` resumes it. Starting or stopping a service that is already in that state exits `0`.

## Ports

Defaults: dashboard <http://localhost:3000>, gateway <http://localhost:8080>, Redis `localhost:6379`. All are bound to `127.0.0.1` only.

To change a port, set `DASHBOARD_PORT`, `GATEWAY_PORT` or `REDIS_PORT` in the shell environment, or copy `.env.example` to `.env` at the repository root and edit it (`.env` is git-ignored; the environment wins over `.env`). If `start` fails with "ports are not available", another process uses that port; pick another one this way.

```sh
GATEWAY_PORT=9090 sh scripts/start.sh
```

```bat
set GATEWAY_PORT=9090
scripts\start.cmd
```

## Demo: Redis outage

The gateway keeps serving and stays `UP` without Redis.

```sh
sh scripts/start.sh
sh scripts/stop-redis.sh
curl http://localhost:8080/actuator/health
sh scripts/start-redis.sh
```

```bat
scripts\start.cmd
scripts\stop-redis.cmd
curl http://localhost:8080/actuator/health
scripts\start-redis.cmd
```

`curl` prints `{"status":"UP"}` while Redis is down. Redis comes back empty (no persistence); counters are per period anyway.

## Demo: gateway outage

The dashboard keeps loading and shows that the gateway is unreachable; the generator keeps running.

```sh
sh scripts/stop-gateway.sh
curl -i http://localhost:3000/actuator/health
sh scripts/start-gateway.sh
```

```bat
scripts\stop-gateway.cmd
curl -i http://localhost:3000/actuator/health
scripts\start-gateway.cmd
```

While the gateway is stopped, the health check through the dashboard origin returns `502`; after `start-gateway` returns it is `200` again.

## Stop everything

```sh
sh scripts/stop.sh
```

```bat
scripts\stop.cmd
```
