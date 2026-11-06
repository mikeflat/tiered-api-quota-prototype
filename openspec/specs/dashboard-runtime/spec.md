# dashboard-runtime Specification

## Purpose

Defines how the admin dashboard is built, started in development, served as a container behind nginx with the gateway's admin and health paths on the same origin, and how its source layering is kept, so later dashboard features and the scripts can rely on a stable runtime surface.

## Requirements

### Requirement: Dashboard serves its app shell
The dashboard SHALL answer `GET /` with an HTML page that loads the single-page app, and the app SHALL render a shell with the heading "Quota dashboard". Until the first snapshot arrives the shell shows a loading indicator and no metric values, so no zeros pretend to be data (FR-21 "Initial loading"; the states after that are defined in `dashboard-states`).

#### Scenario: Shell is rendered
- **WHEN** a browser opens the dashboard root URL
- **THEN** the response status is `200` with an HTML content type
- **AND** the rendered page shows the heading "Quota dashboard"
- **AND** until the first snapshot arrives, a loading indicator is shown and no traffic, totals or tenant numbers are shown

### Requirement: Dashboard starts in development mode
A developer SHALL be able to start the dashboard locally with one documented command after installing dependencies, and it SHALL serve the app shell on port `5173`.

#### Scenario: Dev server answers
- **WHEN** a developer runs the documented dev command in `/dashboard`
- **THEN** `GET http://localhost:5173/` returns `200` with the app's HTML page

### Requirement: Production build is type-checked
The dashboard SHALL build into static files with one documented command, and the build SHALL fail when the TypeScript sources do not type-check.

#### Scenario: Clean build
- **WHEN** a developer runs the documented build command on the unchanged skeleton
- **THEN** the command exits successfully and produces an `index.html` with its static assets

#### Scenario: Type error fails the build
- **WHEN** a source file contains a type error and the build command runs
- **THEN** the command exits with a non-zero status

### Requirement: Admin and health paths share the dashboard origin
The dashboard origin SHALL forward requests under `/admin/` and to `/actuator/health` to the gateway, with method, path, query and body unchanged, both in the container and in development mode, so the browser needs no CORS (D13). The gateway address SHALL be configurable without code changes (NFR-8).

#### Scenario: Health is forwarded to a running gateway
- **WHEN** the gateway is running at the configured address and a client sends `GET /actuator/health` to the dashboard origin
- **THEN** the response is the gateway's response: status `200` with `"status":"UP"`

#### Scenario: Gateway address from configuration
- **WHEN** the dashboard is started with the gateway address set to a non-default host and port where a gateway is running
- **THEN** `GET /actuator/health` on the dashboard origin is answered by that gateway

#### Scenario: Gateway is down
- **WHEN** no gateway is reachable at the configured address and a client sends `GET /actuator/health` to the dashboard container
- **THEN** the dashboard answers with a `5xx` status within a few seconds instead of hanging
- **AND** `GET /` on the same origin still returns the app shell with `200`

#### Scenario: Other gateway paths are not forwarded
- **WHEN** a client sends `GET /api/v1/resource` or `GET /actuator/env` to the dashboard origin
- **THEN** the request is not forwarded to the gateway

### Requirement: Dashboard runs as a container image
The dashboard SHALL be buildable into a container image from `/dashboard` with only Docker on the host. The container SHALL serve the built app and the forwarded paths on port `8080`, run its server as a non-root user, and report itself healthy through a container healthcheck that does not depend on the gateway.

#### Scenario: Build and run with Docker only
- **WHEN** an operator runs `docker build` in `/dashboard` and runs the image with container port `8080` published as `3000`
- **THEN** `GET http://localhost:3000/` returns `200` with the app's HTML page
- **AND** the container's health status becomes `healthy` even when no gateway is running

#### Scenario: Server process is not root
- **WHEN** the dashboard container is running
- **THEN** the web server processes run as a non-root user

### Requirement: Source layering is enforced
The dashboard source SHALL be split into domain, application, API adapter and UI layers whose dependencies point inward only, and the documented lint command SHALL fail when domain code imports an outer layer or React, when application code imports adapters or UI, or when domain, application or UI code calls `fetch` directly.

#### Scenario: Clean skeleton passes lint
- **WHEN** the documented lint command runs on the unchanged skeleton
- **THEN** it exits successfully

#### Scenario: Domain importing React is rejected
- **WHEN** a file in the domain layer imports `react` and the lint command runs
- **THEN** the command exits with a non-zero status naming the forbidden import

#### Scenario: UI calling fetch is rejected
- **WHEN** a UI component calls `fetch` and the lint command runs
- **THEN** the command exits with a non-zero status naming the forbidden global

### Requirement: Dashboard has an automated test run
The dashboard SHALL have one documented command that runs its component tests headlessly with React Testing Library and exits non-zero on any failure (NFR-9).

#### Scenario: Shell smoke test passes
- **WHEN** a developer runs the documented test command on the unchanged skeleton
- **THEN** the shell test runs and passes and the command exits successfully
