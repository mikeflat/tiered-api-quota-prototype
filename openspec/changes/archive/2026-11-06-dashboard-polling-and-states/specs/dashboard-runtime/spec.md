# Spec Delta

## MODIFIED Requirements

### Requirement: Dashboard serves its app shell
The dashboard SHALL answer `GET /` with an HTML page that loads the single-page app, and the app SHALL render a shell with the heading "Quota dashboard". Until the first snapshot arrives the shell shows a loading indicator and no metric values, so no zeros pretend to be data (FR-21 "Initial loading"; the states after that are defined in `dashboard-states`).

#### Scenario: Shell is rendered
- **WHEN** a browser opens the dashboard root URL
- **THEN** the response status is `200` with an HTML content type
- **AND** the rendered page shows the heading "Quota dashboard"
- **AND** until the first snapshot arrives, a loading indicator is shown and no traffic, totals or tenant numbers are shown
