import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { DashboardView } from '../../../src/domain/dashboardView.ts'
import type { TenantGridView } from '../../../src/domain/tenantGrid.ts'
import { initialTenantQuery } from '../../../src/domain/tenantQuery.ts'
import { metricsWith, minuteOfTraffic, snapshotWith, snapshotWithoutMetrics } from '../../support/snapshots.ts'
import { DashboardScreen } from '../../../src/ui/components/DashboardScreen.tsx'
import type { TenantsRegionModel } from '../../../src/ui/components/TenantsRegion.tsx'
import { recordingActions } from '../../support/tenantQueryActions.ts'

const liveView: DashboardView = {
  loading: false,
  gatewayUnreachable: false,
  snapshot: snapshotWith(),
  stale: false,
  lastUpdatedSecondsAgo: null,
  reconnectedNoticeVisible: false,
  enforcementDegraded: false,
  metricsAvailable: true,
  tierControlsEnabled: true,
  connectionStatus: 'connected',
  enforcement: 'ok',
  secondsUntilReset: 15,
  shownQuery: initialTenantQuery,
}

const loadingView: DashboardView = {
  ...liveView,
  loading: true,
  snapshot: null,
  metricsAvailable: false,
  tierControlsEnabled: false,
  connectionStatus: 'connecting',
  enforcement: 'unknown',
  secondsUntilReset: null,
}

function header(): HTMLElement {
  return screen.getByRole('banner')
}

const noTenants: TenantsRegionModel = {
  grid: null,
  filters: {
    nameText: '',
    idText: '',
    idInvalid: false,
    tier: null,
    status: null,
    tierOptions: ['Bronze', 'Silver', 'Gold'],
    enabled: true,
    statusEnabled: true,
  },
  actions: recordingActions(),
  onTierChange: () => {},
}

function regionNamed(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

describe('DashboardScreen', () => {
  it('loading_view_shows_indicator_and_no_digits', () => {
    render(<DashboardScreen tenants={noTenants} view={loadingView} />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading dashboard')
    expect(screen.queryByRole('region')).not.toBeInTheDocument()
    expect(screen.getByRole('main')).not.toHaveTextContent(/\d/)
  })

  it('live_view_shows_three_regions_without_banners', () => {
    render(<DashboardScreen tenants={noTenants} view={liveView} />)

    expect(regionNamed('Traffic')).toBeInTheDocument()
    expect(regionNamed('Period totals')).toBeInTheDocument()
    expect(regionNamed('Tenants')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByTestId('data-regions')).not.toHaveClass('is-stale')
  })

  it('stale_view_greys_regions_and_shows_last_updated', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, gatewayUnreachable: true, stale: true, lastUpdatedSecondsAgo: 7 }} />)

    const regions = screen.getByTestId('data-regions')
    expect(regions).toHaveClass('is-stale')
    expect(regions).toHaveAccessibleDescription(/last updated 7 s ago/)
    expect(screen.getByRole('alert')).toHaveTextContent('Gateway unreachable')
  })

  it('metrics_unavailable_marks_traffic_and_totals_only', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, snapshot: snapshotWithoutMetrics(), metricsAvailable: false }} />)

    expect(regionNamed('Traffic')).toHaveTextContent('Metrics unavailable')
    expect(regionNamed('Period totals')).toHaveTextContent('Metrics unavailable')
    expect(regionNamed('Tenants')).not.toHaveTextContent('Metrics unavailable')
  })

  it('degraded_view_shows_yellow_banner_text', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, enforcementDegraded: true }} />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Quota enforcement degraded: no new blocks, counting paused')
    expect(banner).toHaveClass('banner-warning')
  })

  it('reconnected_view_shows_notice', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, reconnectedNoticeVisible: true }} />)

    expect(screen.getByRole('status')).toHaveTextContent('Reconnected')
  })

  it('banners_render_above_regions', () => {
    render(
      <DashboardScreen
        tenants={noTenants}
        view={{ ...liveView, gatewayUnreachable: true, stale: true, lastUpdatedSecondsAgo: 3, enforcementDegraded: true }}
      />,
    )

    const regions = screen.getByTestId('data-regions')
    for (const banner of screen.getAllByRole('alert')) {
      expect(banner.compareDocumentPosition(regions) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
  })

  it('header_shows_connecting_and_dash_while_loading', () => {
    render(<DashboardScreen tenants={noTenants} view={loadingView} />)

    expect(header()).toHaveTextContent('Gateway Connecting')
    expect(header()).toHaveTextContent('Enforcement Unknown')
    expect(header()).toHaveTextContent('Resets in —')
    expect(header()).not.toHaveTextContent(/\d/)
  })

  it('header_shows_connected_ok_and_countdown_when_live', () => {
    render(<DashboardScreen tenants={noTenants} view={liveView} />)

    expect(header()).toHaveTextContent('Gateway Connected')
    expect(header()).toHaveTextContent('Enforcement OK')
    expect(header()).toHaveTextContent('Resets in 0:15')
  })

  it('header_shows_unreachable_and_dash_when_stale', () => {
    render(
      <DashboardScreen
        tenants={noTenants}
        view={{
          ...liveView,
          gatewayUnreachable: true,
          stale: true,
          lastUpdatedSecondsAgo: 7,
          connectionStatus: 'unreachable',
          secondsUntilReset: null,
        }}
      />,
    )

    expect(header()).toHaveTextContent('Gateway Unreachable')
    expect(header()).toHaveTextContent('Resets in —')
    expect(screen.getByText('Unreachable')).toHaveClass('status-error')
  })

  it('header_shows_degraded_enforcement', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, enforcement: 'degraded', enforcementDegraded: true }} />)

    expect(header()).toHaveTextContent('Enforcement Degraded')
    expect(screen.getByText('Degraded')).toHaveClass('status-warning')
  })

  it('header_shows_unknown_enforcement', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, enforcement: 'unknown' }} />)

    expect(header()).toHaveTextContent('Enforcement Unknown')
  })

  it('loading_main_area_still_has_no_digits', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...loadingView, gatewayUnreachable: true, connectionStatus: 'unreachable' }} />)

    expect(screen.getByRole('main')).not.toHaveTextContent(/\d/)
    expect(header()).toHaveTextContent('Gateway Unreachable')
  })

  it('live_view_shows_traffic_and_totals_numbers', () => {
    const metrics = metricsWith({
      perSecond: minuteOfTraffic(6_000_044, (secondsAgo) =>
        secondsAgo === 0 ? { allowed: 183, rejected: 12 } : { allowed: 100, rejected: 0 },
      ),
      totals: { allowed: 12_045, rejected: 731 },
      tenantsNear: 2,
      tenantsOver: 1,
    })

    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, snapshot: snapshotWith({ metrics }) }} />)

    expect(regionNamed('Traffic')).toHaveTextContent('Allowed 183 req/s')
    expect(regionNamed('Traffic')).toHaveTextContent('Rejected 12 req/s')
    expect(within(regionNamed('Traffic')).getByRole('img', { name: /last 60 seconds/ })).toBeInTheDocument()
    expect(regionNamed('Period totals')).toHaveTextContent('Allowed 12,045')
    expect(regionNamed('Period totals')).toHaveTextContent('Rejected 731')
    expect(regionNamed('Period totals')).toHaveTextContent('Near limit 2')
    expect(regionNamed('Period totals')).toHaveTextContent('Over limit 1')
  })

  it('metrics_unavailable_shows_no_digits_or_trend_in_traffic_and_totals', () => {
    render(<DashboardScreen tenants={noTenants} view={{ ...liveView, snapshot: snapshotWithoutMetrics(), metricsAvailable: false }} />)

    for (const name of ['Traffic', 'Period totals']) {
      expect(regionNamed(name)).toHaveTextContent('Metrics unavailable')
      expect(regionNamed(name)).not.toHaveTextContent(/\d/)
    }
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('stale_view_keeps_last_numbers_greyed', () => {
    render(
      <DashboardScreen
        tenants={noTenants}
        view={{
          ...liveView,
          gatewayUnreachable: true,
          stale: true,
          lastUpdatedSecondsAgo: 4,
          connectionStatus: 'unreachable',
          secondsUntilReset: null,
        }}
      />,
    )

    expect(screen.getByTestId('data-regions')).toHaveClass('is-stale')
    expect(regionNamed('Traffic')).toHaveTextContent('Allowed 3 req/s')
    expect(regionNamed('Period totals')).toHaveTextContent('Allowed 120')
  })

  it('tenants_region_shows_grid', () => {
    const grid: TenantGridView = {
      kind: 'rows',
      rows: [
        {
          id: 1,
          name: 'Acme Corp',
          tier: 'Bronze',
          limit: 100,
          used: 85,
          rejected: 0,
          status: 'near',
          usagePercent: 85,
          tierOptions: [{ name: 'Bronze', limit: 100 }],
          saving: false,
          failure: null,
          selectorEnabled: true,
        },
      ],
      shownCount: 1,
      total: 1,
      pageNumber: 1,
      pageCount: 1,
      canPrevious: false,
      canNext: false,
      updating: false,
    }

    render(<DashboardScreen tenants={{ ...noTenants, grid }} view={liveView} />)

    expect(within(regionNamed('Tenants')).getByRole('table')).toHaveTextContent('Acme Corp')
    expect(regionNamed('Tenants')).toHaveTextContent('Showing 1 of 1 tenants')
  })

  it('tenants_region_shows_filters_above_grid', () => {
    const grid: TenantGridView = {
      kind: 'rows',
      rows: [],
      shownCount: 0,
      total: 0,
      pageNumber: 1,
      pageCount: 1,
      canPrevious: false,
      canNext: false,
      updating: false,
    }

    render(<DashboardScreen tenants={{ ...noTenants, grid }} view={liveView} />)

    const filters = within(regionNamed('Tenants')).getByRole('form', { name: 'Tenant filters' })
    const pages = within(regionNamed('Tenants')).getByRole('navigation', { name: 'Tenant pages' })
    expect(filters.compareDocumentPosition(pages) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(regionNamed('Tenants')).toHaveTextContent('No tenants')
  })
})
