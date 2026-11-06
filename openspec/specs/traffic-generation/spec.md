# traffic-generation Specification

## Purpose

Defines the business traffic the generator sends to the gateway — which tenants, at what total rate and with what weights — and how it reports outcomes and behaves while the gateway is unavailable, so the dashboard always has uneven, live traffic to show.

## Requirements

### Requirement: Tenants come from the shared tenants file
The generator SHALL read the tenant ids from the shared `tenants.json` (a JSON array of objects with a positive integer `id`; other fields are ignored), located by `TENANTS_FILE`, defaulting to the repository's `config/tenants.json` when run from sources (FR-24).

#### Scenario: Seeded tenants are loaded
- **WHEN** the generator starts with the repository's `config/tenants.json`
- **THEN** its startup line reports 15 tenants

#### Scenario: Path override
- **WHEN** the generator starts with `TENANTS_FILE` naming another valid tenants file with 3 tenants
- **THEN** its startup line reports 3 tenants and it sends traffic only for those 3 ids

#### Scenario: Missing or invalid tenants file stops startup
- **WHEN** the generator starts and the tenants file is missing, is not valid JSON, is an empty array, has an entry without a positive integer `id`, or repeats an `id`
- **THEN** the process exits within a few seconds with a non-zero exit code before sending any request
- **AND** the error output names the file path and the problem

### Requirement: Traffic shape lives in the generator's own config file
The generator SHALL read its traffic shape from a JSON file located by `TRAFFIC_CONFIG_FILE`, defaulting to `generator/traffic.json`, with `ratePerSecond` (required, number > 0 and ≤ 5000), `defaultWeight` (≥ 0, default 1), `weights` (tenant id → number ≥ 0, default empty), `maxInFlight` (integer ≥ 1, default 100) and `requestTimeoutMs` (integer ≥ 1, default 2000) (FR-24, NFR-8).

#### Scenario: Shape changes without code changes
- **WHEN** an operator edits `ratePerSecond` in the traffic config file and restarts the generator
- **THEN** the startup line reports the new rate and traffic is paced to it

#### Scenario: Unlisted tenants get the default weight
- **WHEN** the config lists weights for some tenants only
- **THEN** every other tenant from the tenants file is chosen with `defaultWeight`

#### Scenario: Invalid traffic config stops startup
- **WHEN** the config file is missing or not valid JSON, `ratePerSecond` is absent, zero, negative or above 5000, a weight is negative or not a number, an unknown top-level key is present, or every tenant ends up with weight 0
- **THEN** the process exits within a few seconds with a non-zero exit code before sending any request
- **AND** the error output names the file path and the offending field

#### Scenario: Weight for an unknown tenant stops startup
- **WHEN** `weights` contains an id that is not in the tenants file
- **THEN** the process exits with a non-zero exit code and the error output names that id

### Requirement: Requests are paced to the total rate
The generator SHALL start requests continuously at `ratePerSecond` in total, measured from wall-clock time so timer drift does not lower the rate, and SHALL never start more than one second's worth of requests at once to catch up after a pause.

#### Scenario: Rate is held
- **WHEN** the generator runs for 10 s at 200 req/s with a gateway that answers promptly
- **THEN** between 1,900 and 2,100 requests are started in that interval

#### Scenario: Catch-up after a stall is bounded
- **WHEN** the generator's event loop is blocked for 5 s at 200 req/s
- **THEN** at most 200 requests are started immediately afterwards and pacing then continues at 200 req/s

### Requirement: Each request picks a tenant by weight
For each request the generator SHALL pick one tenant at random with probability proportional to its weight; a tenant with weight 0 SHALL never be picked.

#### Scenario: Weighted distribution
- **WHEN** tenants A, B and C have weights 1, 3 and 0 and 40,000 requests are picked
- **THEN** about 25% go to A and about 75% go to B, each within 2 percentage points
- **AND** none go to C

### Requirement: Requests target the business endpoint
Each request SHALL be `GET <GATEWAY_URL>/api/v1/resource` with the header `X-Tenant-Id` set to the picked tenant's id, reusing connections (keep-alive) and reading or discarding each response body (FR-23).

#### Scenario: Request shape
- **WHEN** the generator sends a request for tenant 7 with `GATEWAY_URL=http://gateway:8080`
- **THEN** the gateway receives `GET /api/v1/resource` with `X-Tenant-Id: 7`

### Requirement: Outcomes are classified
The generator SHALL classify every started request as exactly one of: `200` (status 200), `429` (status 429), or error (any other status, a connection failure, or no response within `requestTimeoutMs`).

#### Scenario: Over-limit tenant
- **WHEN** the gateway answers `429` with `Retry-After`
- **THEN** the request is counted as `429`, not as an error, and the tenant keeps being picked by weight

#### Scenario: Unexpected status
- **WHEN** the gateway answers `400` or `503`
- **THEN** the request is counted as an error

#### Scenario: Slow gateway
- **WHEN** the gateway does not answer within `requestTimeoutMs`
- **THEN** the request is abandoned and counted as an error

### Requirement: Generator survives gateway unavailability
While the gateway is unreachable or failing, the generator SHALL keep running and keep starting requests at the configured rate, never exiting or crashing on request errors, and SHALL resume normal traffic without a restart once the gateway is back (FR-26, S8).

#### Scenario: Gateway stopped
- **WHEN** the gateway is stopped while the generator runs
- **THEN** the generator keeps running and its summaries show errors instead of `200`/`429`

#### Scenario: Gateway comes back
- **WHEN** the gateway is started again
- **THEN** within one summary interval the generator's summary shows `200` responses again, without a generator restart

### Requirement: In-flight requests are bounded
The generator SHALL keep at most `maxInFlight` requests in flight; a request that is due while the limit is reached SHALL not be sent or queued but counted as skipped.

#### Scenario: Hanging gateway
- **WHEN** the gateway accepts connections but never answers, with `maxInFlight` 100
- **THEN** no more than 100 requests are in flight at any time
- **AND** due requests beyond that are counted as skipped and memory use does not grow with the outage length

### Requirement: Periodic summary is logged
Every 10 s the generator SHALL log one summary line for that interval with the counts of requests sent, `200`, `429`, errors and skipped, and, when there were errors, the reason of the last one (FR-26).

#### Scenario: Healthy interval
- **WHEN** 10 s pass with the gateway answering
- **THEN** one line is logged containing `sent=`, `200=`, `429=`, `errors=` and `skipped=` with that interval's counts, and the counts start from zero for the next interval

#### Scenario: Interval with errors
- **WHEN** requests fail because the gateway is unreachable
- **THEN** the summary line includes a short reason of the last error, such as a connection-refused code or `timeout`
- **AND** no line is logged per failed request

### Requirement: Default shape produces all three states
The default traffic config SHALL, at its default rate and the default prototype settings (60 s period, limits 100 / 1,000 / 10,000, near threshold 80%), give expected per-period request counts that put at least one tenant in each of ok, near and over (FR-25). These are starting values; tuning against observed runs belongs to `calibration-and-demo`.

#### Scenario: Expected counts per period
- **WHEN** the expected requests per period of each seeded tenant are computed as `ratePerSecond × 60 × weight / total weight`
- **THEN** at least one tenant is expected below 80% of its tier limit, at least one between 80% and 100%, and at least one above 100%
