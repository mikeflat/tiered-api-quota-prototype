# Spec Delta

## ADDED Requirements

### Requirement: A tenant's tier can be changed in memory
The registry SHALL replace the tier of an existing tenant, keeping its id and name, and SHALL report "not found" for an unknown id without changing anything (FR-11). The change SHALL be visible to every following lookup and listing, SHALL do no I/O, and SHALL NOT be written to the tenants file.

#### Scenario: Change tier
- **WHEN** tenant `1` is `Acme Corp`, Bronze, and its tier is set to Gold
- **THEN** looking up `1` returns `Acme Corp` with tier Gold

#### Scenario: Unknown tenant
- **WHEN** the tier of id `99` is set and no tenant has that id
- **THEN** the registry reports not found and still holds the same tenants

#### Scenario: Concurrent lookups
- **WHEN** tenant `1`'s tier is changed while other threads look tenants up
- **THEN** each lookup returns tenant `1` with either its old or its new tier, never a missing tenant

### Requirement: Tenants are filtered by name, id and tier
The registry SHALL return the tenants matching all given filters, sorted like the full listing: name contains a text (case-insensitive), exact id, and tier (FR-14). An absent filter SHALL match every tenant.

#### Scenario: Name contains, any case
- **WHEN** the registry holds `Meridian Bank` and `Acme Corp` and is filtered by name `BANK`
- **THEN** only `Meridian Bank` is returned

#### Scenario: Id and tier combined
- **WHEN** tenant `1` is Bronze and the registry is filtered by id `1` and tier Silver
- **THEN** no tenant is returned

#### Scenario: No filters
- **WHEN** the registry is filtered with no filters
- **THEN** the result equals the full listing sorted by name

#### Scenario: Filter after a tier change
- **WHEN** Bronze tenant `1` is changed to Gold and the registry is filtered by tier Gold
- **THEN** tenant `1` is in the result
