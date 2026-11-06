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
        <h1>Quota dashboard</h1>
        <HeaderStatus
          connection={view.connectionStatus}
          enforcement={view.enforcement}
          secondsUntilReset={view.secondsUntilReset}
        />
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
