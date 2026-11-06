# Tiered API Quota Prototype

Per-tenant, fixed-window API quota by tier (Bronze / Silver / Gold), with a live admin dashboard and a traffic generator.

Stack: Redis · gateway (Spring Boot) · dashboard (React) · generator. Task statement: [docs/01 task.md](docs/01%20task.md).

## Start / stop

Requires Docker with compose v2 (2.21+). Every script exists as `.sh` (Linux, macOS, Git Bash) and `.cmd` (Windows).

```sh
sh scripts/start.sh      # build and start everything, wait until up, print URLs
sh scripts/stop.sh       # stop and remove all containers (images are kept)
```

```bat
scripts\start.cmd
scripts\stop.cmd
```

- Dashboard: <http://localhost:3000>
- Gateway: <http://localhost:8080>

Single components: `start-redis` / `stop-redis`, `start-gateway` / `stop-gateway`, `start-generator` / `stop-generator`. Ports and quota settings can be changed in `.env` (see [.env.example](.env.example)). The quota period is 60 s by default, not a full day, so limits reset quickly during a demo. More in [scripts/README.md](scripts/README.md).

## Test scenarios

Start the stack and open the dashboard. The generator sends about 200 req/s, weighted so that several tenants hit their limits in each period.

**Change a tier.** In the tenant grid, move a blocked (over-limit) Bronze tenant to Silver or Gold. It is unblocked at once and gets the new limit. Moving a tenant down to a tier whose limit it already exceeds blocks it again. Tier changes are kept in memory only, so a gateway restart restores the tiers from [config/tenants.json](config/tenants.json).

**Drop Redis.** `stop-redis`: the gateway keeps serving and stays `UP`, but enforcement is degraded: no new blocks, and counting is paused. The dashboard shows a yellow "Quota enforcement degraded" banner and marks metrics as unavailable. Tier changes still work. `start-redis`: the banner goes away and data comes back within about a second.

**Drop the gateway.** `stop-gateway`: after two failed polls the dashboard shows "Gateway unreachable", greys out the last known data and disables the tier controls. `start-gateway`: it shows "Reconnected" and resumes live data.

**Stop traffic.** `stop-generator`: the traffic chart drops to zero. `start-generator`: traffic resumes. To change the traffic shape, edit [generator/traffic.json](generator/traffic.json) and restart the generator.

## Development approach

1. **Discussion first.** We clarified the task and agreed on requirements and architecture before writing code: [discussion](docs/00%20discussion.pdf), [requirements](docs/02%20requirements.md), [architecture](docs/03%20architecture.md).
2. **Backlog.** We split the work into small, independently deliverable items: [backlog](docs/04%20backlog.md).
3. **OpenSpec cycle per item.** For each item: *propose* (proposal, design, spec deltas, tasks) → review → *apply* (implement with tests) → *archive* (merge the spec deltas into [openspec/specs](openspec/specs)). Completed changes are in [openspec/changes/archive](openspec/changes/archive).
