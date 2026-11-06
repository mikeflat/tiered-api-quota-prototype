import type { DashboardView } from '../../domain/dashboardView.ts'
import { DataRegions } from './DataRegions.tsx'
import { DegradedEnforcementBanner } from './DegradedEnforcementBanner.tsx'
import { GatewayUnreachableBanner } from './GatewayUnreachableBanner.tsx'
import { LastUpdatedNote } from './LastUpdatedNote.tsx'
import { LoadingIndicator } from './LoadingIndicator.tsx'
import { ReconnectedNotice } from './ReconnectedNotice.tsx'

const lastUpdatedNoteId = 'last-updated-note'

export function DashboardScreen({ view }: { view: DashboardView }) {
  return (
    <div className="app">
      <header className="app-header">
        <h1>Quota dashboard</h1>
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
              metricsAvailable={view.metricsAvailable}
              describedBy={view.stale ? lastUpdatedNoteId : undefined}
            />
          </>
        )}
      </main>
    </div>
  )
}
