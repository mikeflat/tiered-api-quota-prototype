import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { DashboardView } from '../../../src/domain/dashboardView.ts'
import { snapshotWith, snapshotWithoutMetrics } from '../../support/snapshots.ts'
import { DashboardScreen } from '../../../src/ui/components/DashboardScreen.tsx'

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
}

const loadingView: DashboardView = {
  ...liveView,
  loading: true,
  snapshot: null,
  metricsAvailable: false,
  tierControlsEnabled: false,
}

function regionNamed(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

describe('DashboardScreen', () => {
  it('loading_view_shows_indicator_and_no_digits', () => {
    render(<DashboardScreen view={loadingView} />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading dashboard')
    expect(screen.queryByRole('region')).not.toBeInTheDocument()
    expect(screen.getByRole('main')).not.toHaveTextContent(/\d/)
  })

  it('live_view_shows_three_regions_without_banners', () => {
    render(<DashboardScreen view={liveView} />)

    expect(regionNamed('Traffic')).toBeInTheDocument()
    expect(regionNamed('Period totals')).toBeInTheDocument()
    expect(regionNamed('Tenants')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByTestId('data-regions')).not.toHaveClass('is-stale')
  })

  it('stale_view_greys_regions_and_shows_last_updated', () => {
    render(<DashboardScreen view={{ ...liveView, gatewayUnreachable: true, stale: true, lastUpdatedSecondsAgo: 7 }} />)

    const regions = screen.getByTestId('data-regions')
    expect(regions).toHaveClass('is-stale')
    expect(regions).toHaveAccessibleDescription(/last updated 7 s ago/)
    expect(screen.getByRole('alert')).toHaveTextContent('Gateway unreachable')
  })

  it('metrics_unavailable_marks_traffic_and_totals_only', () => {
    render(<DashboardScreen view={{ ...liveView, snapshot: snapshotWithoutMetrics(), metricsAvailable: false }} />)

    expect(regionNamed('Traffic')).toHaveTextContent('Metrics unavailable')
    expect(regionNamed('Period totals')).toHaveTextContent('Metrics unavailable')
    expect(regionNamed('Tenants')).not.toHaveTextContent('Metrics unavailable')
  })

  it('degraded_view_shows_yellow_banner_text', () => {
    render(<DashboardScreen view={{ ...liveView, enforcementDegraded: true }} />)

    const banner = screen.getByRole('alert')
    expect(banner).toHaveTextContent('Quota enforcement degraded: no new blocks, counting paused')
    expect(banner).toHaveClass('banner-warning')
  })

  it('reconnected_view_shows_notice', () => {
    render(<DashboardScreen view={{ ...liveView, reconnectedNoticeVisible: true }} />)

    expect(screen.getByRole('status')).toHaveTextContent('Reconnected')
  })

  it('banners_render_above_regions', () => {
    render(
      <DashboardScreen
        view={{ ...liveView, gatewayUnreachable: true, stale: true, lastUpdatedSecondsAgo: 3, enforcementDegraded: true }}
      />,
    )

    const regions = screen.getByTestId('data-regions')
    for (const banner of screen.getAllByRole('alert')) {
      expect(banner.compareDocumentPosition(regions) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
  })
})
