# quota-configuration Specification

## Purpose

Defines the gateway's static quota settings — period length, request limit per tier and near-limit threshold — with their defaults, how they are overridden without code changes and how invalid values are rejected, so enforcement, metrics and the admin API share one validated set of settings.

## Requirements

### Requirement: Quota settings have prototype defaults
Without overrides the gateway SHALL use a period of 60 seconds, limits per period of 100 for Bronze, 1,000 for Silver and 10,000 for Gold, and a near-limit threshold of 80 percent (FR-10, docs/02 section 7).

#### Scenario: Defaults
- **WHEN** the gateway starts with none of the quota environment variables set
- **THEN** the effective settings are period 60 s, Bronze 100, Silver 1000, Gold 10000, near threshold 80 %

### Requirement: Quota settings are overridable without code changes
Each setting SHALL be overridable by an environment variable: `QUOTA_PERIOD_SECONDS`, `QUOTA_LIMIT_BRONZE`, `QUOTA_LIMIT_SILVER`, `QUOTA_LIMIT_GOLD`, `QUOTA_NEAR_THRESHOLD_PERCENT` (NFR-8, D4). Settings not overridden keep their defaults.

#### Scenario: Period override
- **WHEN** the gateway starts with `QUOTA_PERIOD_SECONDS=86400`
- **THEN** the effective period is 86400 s and the other settings keep their defaults

#### Scenario: Limit and threshold override
- **WHEN** the gateway starts with `QUOTA_LIMIT_GOLD=500` and `QUOTA_NEAR_THRESHOLD_PERCENT=90`
- **THEN** the effective Gold limit is 500, the near threshold is 90 %, and Bronze and Silver keep 100 and 1000

### Requirement: Every tier has a limit
Every tier (Bronze, Silver, Gold) SHALL have exactly one configured limit, and the gateway SHALL be able to give the limit of any tier without I/O.

#### Scenario: Limit of each tier
- **WHEN** the limit for Bronze, Silver and Gold is requested with default settings
- **THEN** the answers are 100, 1000 and 10000

### Requirement: Invalid quota settings stop startup
The gateway SHALL refuse to start, exiting with a non-zero code and an error naming the setting and the rejected value, when the period is not a whole number of seconds of at least 1, a tier limit is not a whole number of at least 1, or the near threshold is not a whole number from 1 to 99.

#### Scenario: Zero period
- **WHEN** the gateway starts with `QUOTA_PERIOD_SECONDS=0`
- **THEN** startup fails with an error naming the period setting and `0`

#### Scenario: Negative limit
- **WHEN** the gateway starts with `QUOTA_LIMIT_SILVER=-5`
- **THEN** startup fails with an error naming the Silver limit and `-5`

#### Scenario: Threshold out of range
- **WHEN** the gateway starts with `QUOTA_NEAR_THRESHOLD_PERCENT=100` or `QUOTA_NEAR_THRESHOLD_PERCENT=0`
- **THEN** startup fails with an error naming the near threshold setting and the value

#### Scenario: Not a number
- **WHEN** the gateway starts with `QUOTA_LIMIT_BRONZE=lots`
- **THEN** startup fails with an error naming the Bronze limit and `lots`

### Requirement: Near-limit boundary follows the threshold
For a limit `L` and threshold `T` percent, the near-limit boundary SHALL be the smallest whole number of requests `n` with `n × 100 ≥ L × T`, so a tenant is near its limit when `n ≤ used < L` (docs/02 section 2, "Near limit"). The boundary SHALL be computed without I/O.

#### Scenario: Default Bronze boundary
- **WHEN** the limit is 100 and the threshold is 80 %
- **THEN** the near-limit boundary is 80

#### Scenario: Boundary rounds up
- **WHEN** the limit is 7 and the threshold is 80 %
- **THEN** the near-limit boundary is 6

#### Scenario: Large limit
- **WHEN** the limit is 10000 and the threshold is 80 %
- **THEN** the near-limit boundary is 8000

### Requirement: Startup reports the effective quota settings
After the settings are validated the gateway SHALL log one line with the period length, the limit of each tier and the near threshold.

#### Scenario: Startup log
- **WHEN** the gateway starts with default settings
- **THEN** its log contains one line naming period 60 s, Bronze 100, Silver 1000, Gold 10000 and near threshold 80 %
