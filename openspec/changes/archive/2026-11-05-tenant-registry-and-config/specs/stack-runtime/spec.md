# Spec Delta

## MODIFIED Requirements

### Requirement: Services are wired together inside the stack
Inside the stack the gateway SHALL be configured to reach Redis by its service name, the dashboard SHALL forward `/admin/**` and `/actuator/health` to the gateway, and the generator SHALL target the gateway, all without host-specific settings. The repository's `config/tenants.json` SHALL be mounted read-only into the gateway at `/config/tenants.json`, and the quota settings `QUOTA_PERIOD_SECONDS`, `QUOTA_LIMIT_BRONZE`, `QUOTA_LIMIT_SILVER`, `QUOTA_LIMIT_GOLD` and `QUOTA_NEAR_THRESHOLD_PERCENT` SHALL be passed to the gateway from the shell environment or a root `.env` file when set.

#### Scenario: Gateway health through the dashboard origin
- **WHEN** the stack has been started with the `start` script
- **THEN** `GET http://localhost:3000/actuator/health` returns `200` with `"status":"UP"`
- **AND** `GET http://localhost:3000/` returns the dashboard app shell with `200`

#### Scenario: Gateway configured with the Redis service
- **WHEN** the stack is running
- **THEN** the gateway container's Redis host setting is `redis` and port is `6379`
- **AND** `redis-cli ping` inside the Redis container answers `PONG`

#### Scenario: Generator targets the gateway service
- **WHEN** the stack is running
- **THEN** the generator log shows its startup line naming `http://gateway:8080`

#### Scenario: Gateway reads the shared tenants file
- **WHEN** the stack is running
- **THEN** the gateway container has `config/tenants.json` mounted read-only at `/config/tenants.json`
- **AND** the gateway log shows 15 tenants loaded from `/config/tenants.json`

#### Scenario: Quota setting from the root .env
- **WHEN** the root `.env` sets `QUOTA_PERIOD_SECONDS=30` and the stack is started
- **THEN** the gateway log shows an effective period of 30 s

#### Scenario: Quota settings not set
- **WHEN** no quota variable is set in the environment or `.env` and the stack is started
- **THEN** the gateway log shows the default settings: period 60 s, Bronze 100, Silver 1000, Gold 10000, near threshold 80 %
