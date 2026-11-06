# Spec Delta

## ADDED Requirements

### Requirement: Tier change removes the tenant from the blocked set
A tier change SHALL remove the tenant from the blocked set of the current period, with no I/O, so its next request is admitted and its next applied event decides whether it is blocked again (FR-11, S6). Other blocked tenants SHALL stay blocked.

#### Scenario: Blocked tenant changes tier
- **WHEN** tenants `1` and `2` are blocked and tenant `1`'s tier is changed
- **THEN** tenant `1`'s next request gets `200` and tenant `2`'s next request gets `429`

#### Scenario: Tenant not blocked
- **WHEN** tenant `3` is not blocked and its tier is changed
- **THEN** its next request gets `200` and no other tenant's blocking changes

#### Scenario: Redis unreachable
- **WHEN** Redis is unreachable, tenant `1` is blocked, and its tier is changed
- **THEN** tenant `1`'s next request gets `200`
- **AND** it is not blocked again during the outage, because no event is applied

### Requirement: Blocking uses the limit current when the tenant is blocked
The writer SHALL block a tenant only if the returned `used` reaches both the limit the event was applied with and the limit of the tenant's tier at the moment it is added to the blocked set, so an event checked against the old limit cannot leave the tenant blocked after a tier change (FR-6, FR-11).

#### Scenario: Event in flight during an upgrade
- **WHEN** the Bronze limit is `5`, the Silver limit is `50`, an allowed event of Bronze tenant `1` returns `used` `5` from Redis, and tenant `1` is changed to Silver before the writer blocks it
- **THEN** tenant `1` is not blocked and its next request gets `200`

#### Scenario: Event in flight during a downgrade
- **WHEN** the Silver limit is `50`, the Bronze limit is `5`, an allowed event of Silver tenant `9` was applied with the Silver limit and returned `used` `6`, and tenant `9` was changed to Bronze meanwhile
- **THEN** that event does not block tenant `9`
- **AND** its next applied allowed event, evaluated with the Bronze limit, blocks it
