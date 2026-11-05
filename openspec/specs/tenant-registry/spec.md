# tenant-registry Specification

## Purpose

Defines how the gateway learns its tenants: the shared `tenants.json` seed file, how it is validated and loaded at startup, and how tenants are looked up and listed from gateway memory, so the tenant guard, quota recording and the admin API can rely on one in-memory registry.

## Requirements

### Requirement: Seed file lists the prototype tenants
The repository SHALL contain `config/tenants.json`, a JSON array of tenant objects, each with an integer `id` (a JSON whole number of at least 1), a string `name` and a `tier` of `Bronze`, `Silver` or `Gold`. The seed SHALL hold 15 tenants: 8 Bronze, 5 Silver and 2 Gold, with unique ids (FR-9, NFR-7).

#### Scenario: Seed composition
- **WHEN** `config/tenants.json` is read
- **THEN** it is a JSON array of 15 objects with a positive integer `id` and non-blank `name` and `tier`
- **AND** 8 have tier `Bronze`, 5 `Silver` and 2 `Gold`
- **AND** no two objects share an `id`

### Requirement: Registry is seeded from the tenants file at startup
The gateway SHALL load every tenant from the tenants file once at startup into an in-memory registry and SHALL NOT read the file again while it runs (FR-9). The file path SHALL come from the `TENANTS_FILE` environment variable, defaulting to `../config/tenants.json` relative to the working directory.

#### Scenario: Seeded registry after startup
- **WHEN** the gateway starts with `TENANTS_FILE` pointing at the repository's `config/tenants.json`
- **THEN** the registry holds 15 tenants with the ids, names and tiers from the file

#### Scenario: File path from the environment
- **WHEN** the gateway starts with `TENANTS_FILE` pointing at another valid file holding 2 tenants
- **THEN** the registry holds exactly those 2 tenants

#### Scenario: File changed after startup
- **WHEN** the tenants file is edited while the gateway runs
- **THEN** the registry still holds the tenants loaded at startup

### Requirement: Tier names are read case-insensitively
The gateway SHALL accept tier values in the tenants file regardless of letter case (`gold`, `GOLD`, `Gold`) and SHALL report tiers by their canonical names `Bronze`, `Silver` and `Gold`.

#### Scenario: Lower-case tier
- **WHEN** the tenants file contains a tenant with `"tier": "silver"`
- **THEN** startup succeeds and that tenant's tier is `Silver`

### Requirement: Invalid tenants file stops startup
The gateway SHALL refuse to start, exiting with a non-zero code and an error that names the tenants file and the problem, when the file is missing or unreadable, is not a JSON array of objects, is empty, contains an entry with a missing field, an invalid `id`, an unknown tier, or a duplicate `id`. Unknown extra fields in an entry SHALL be ignored.

#### Scenario: File missing
- **WHEN** the gateway starts with `TENANTS_FILE` pointing at a path that does not exist
- **THEN** startup fails
- **AND** the error names that path

#### Scenario: Malformed JSON
- **WHEN** the tenants file contains `[{"id": 1,`
- **THEN** startup fails with an error naming the tenants file

#### Scenario: Empty list
- **WHEN** the tenants file contains `[]`
- **THEN** startup fails with an error stating that no tenants are defined

#### Scenario: Unknown tier
- **WHEN** an entry has `"tier": "Platinum"`
- **THEN** startup fails with an error naming the entry's id and the value `Platinum`

#### Scenario: Missing field
- **WHEN** an entry has no `name`, or no `id`
- **THEN** startup fails with an error naming the missing field

#### Scenario: Duplicate id
- **WHEN** two entries have the id `1`
- **THEN** startup fails with an error naming the duplicate id `1`

#### Scenario: Extra field
- **WHEN** an entry also contains `"comment": "demo"`
- **THEN** startup succeeds and the tenant is loaded

### Requirement: Tenant fields have strict JSON types
In the tenants file each `id` SHALL be a JSON whole number of at least 1, and each `name` and `tier` SHALL be a non-blank JSON string. Values SHALL NOT be coerced between JSON types; an entry that breaks this SHALL stop startup with an error naming the entry and the field.

#### Scenario: Id is not a whole number
- **WHEN** an entry has `"id": "1"`, `"id": 1.5` or `"id": "acme"`
- **THEN** startup fails with an error naming the entry and the field `id`

#### Scenario: Id is not positive
- **WHEN** an entry has `"id": 0`
- **THEN** startup fails with an error naming the entry and the value `0`

#### Scenario: Name is not a string
- **WHEN** an entry has `"name": 42`
- **THEN** startup fails with an error naming the entry and the field `name`

### Requirement: Tenants are looked up by id
The registry SHALL return the tenant (id, name, tier) for an integer id that matches exactly and SHALL report "not found" for any other id. Lookup SHALL do no I/O.

#### Scenario: Known id
- **WHEN** the registry is asked for an id present in the seed
- **THEN** it returns that tenant's id, name and tier

#### Scenario: Unknown id
- **WHEN** the registry is asked for `99`
- **THEN** it reports that the tenant is not found

### Requirement: Tenants are listed sorted by name
The registry SHALL list all tenants sorted by name, case-insensitively, with ties broken by id in numeric order, so listings are stable across calls.

#### Scenario: Listing order
- **WHEN** the registry holds tenants named `beta`, `Alpha` and `Gamma`
- **THEN** the listing order is `Alpha`, `beta`, `Gamma`

#### Scenario: Same name
- **WHEN** two tenants share the name `Acme` with ids `10` and `9`
- **THEN** `9` is listed before `10`

### Requirement: Startup reports the loaded tenants
After a successful load the gateway SHALL log one line with the total number of tenants, the count per tier and the tenants file path.

#### Scenario: Startup log
- **WHEN** the gateway starts with the repository's seed file
- **THEN** its log contains one line stating 15 tenants with Bronze 8, Silver 5 and Gold 2, and the file path
