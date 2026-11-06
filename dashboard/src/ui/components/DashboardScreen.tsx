import type { DashboardView } from '../../domain/dashboardView.ts'
import { DataRegions } from './DataRegions.tsx'
import { DegradedEnforcementBanner } from './DegradedEnforcementBanner.tsx'
import { GatewayUnreachableBanner } from './GatewayUnreachableBanner.tsx'
import { HeaderStatus } from './HeaderStatus.tsx'
import { LastUpdatedNote } from './LastUpdatedNote.tsx'
import { LoadingIndicator } from './LoadingIndicator.tsx'
import { ReconnectedNotice } from './ReconnectedNotice.tsx'
import type { TenantsRegionModel } from './TenantsRegion.tsx'

const lastUpdatedNoteId = 'last-updated-note'

interface DashboardScreenProps {
  readonly view: DashboardView
  readonly tenants: TenantsRegionModel
}

export function DashboardScreen({ view, tenants }: DashboardScreenProps) {
  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-inner">
          <h1>
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="m12 14 4-4" />
                <path d="M3.3 19a10 10 0 1 1 17.4 0" />
              </svg>
            </span>
            Quota dashboard
          </h1>
          <HeaderStatus
            connection={view.connectionStatus}
            enforcement={view.enforcement}
            secondsUntilReset={view.secondsUntilReset}
          />
        </div>
      </header>
      <div className="banners">
        {view.gatewayUnreachable && <GatewayUnreachableBanner />}
        {view.enforcementDegraded && <DegradedEnforcementBanner />}
        {view.reconnectedNoticeVisible && <ReconnectedNotice />}
      </div>
      <main className="app-main">
        {view.loading ? (
          <LoadingIndicator />
        ) : (
          <>
            {view.lastUpdatedSecondsAgo !== null && (
              <LastUpdatedNote id={lastUpdatedNoteId} secondsAgo={view.lastUpdatedSecondsAgo} />
            )}
            <DataRegions
              stale={view.stale}
              metrics={view.snapshot?.metrics ?? null}
              tenants={tenants}
              describedBy={view.stale ? lastUpdatedNoteId : undefined}
            />
          </>
        )}
      </main>
    </div>
  )
}
