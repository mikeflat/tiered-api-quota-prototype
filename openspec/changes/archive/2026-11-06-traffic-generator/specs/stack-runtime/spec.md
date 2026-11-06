# Spec Delta

## MODIFIED Requirements

### Requirement: Gateway reads the shared tenants file
The repository's `config/tenants.json` SHALL be mounted read-only into the gateway and into the generator at `/config/tenants.json`, so both share one tenant source.

#### Scenario: Gateway reads the shared tenants file
- **WHEN** the stack is running
- **THEN** the gateway container has `config/tenants.json` mounted read-only at `/config/tenants.json`
- **AND** the gateway log shows 15 tenants loaded from `/config/tenants.json`

#### Scenario: Generator reads the shared tenants file
- **WHEN** the stack is running
- **THEN** the generator container has `config/tenants.json` mounted read-only at `/config/tenants.json`
- **AND** the generator startup line shows `tenants=15`

## ADDED Requirements

### Requirement: Generator traffic shape is editable without a rebuild
The repository's `generator/traffic.json` SHALL be mounted read-only into the generator container as its traffic config, so editing it and restarting the generator with the component scripts applies the new shape without rebuilding the image (NFR-8).

#### Scenario: Rate change applied by restart
- **WHEN** the stack is running, the operator changes `ratePerSecond` in `generator/traffic.json` and runs `stop-generator` then `start-generator`
- **THEN** the new generator startup line shows the new rate
- **AND** no image was rebuilt and no other container was restarted
