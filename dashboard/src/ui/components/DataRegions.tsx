import type { TrafficMetrics } from '../../domain/snapshot.ts'
import { MetricsUnavailable } from './MetricsUnavailable.tsx'
import { PeriodTotalsPanel } from './PeriodTotalsPanel.tsx'
import { type TenantsRegionModel, TenantsRegion } from './TenantsRegion.tsx'
import { TrafficPanel } from './TrafficPanel.tsx'

interface DataRegionsProps {
  readonly stale: boolean
  readonly metrics: TrafficMetrics | null
  readonly tenants: TenantsRegionModel
  readonly describedBy?: string
}

export function DataRegions({ stale, metrics, tenants, describedBy }: DataRegionsProps) {
  return (
    <div className={stale ? 'data-regions is-stale' : 'data-regions'} data-testid="data-regions" aria-describedby={describedBy}>
      <section className="region" aria-labelledby="traffic-heading">
        <h2 id="traffic-heading">Traffic</h2>
        {metrics === null ? <MetricsUnavailable /> : <TrafficPanel perSecond={metrics.perSecond} />}
      </section>
      <section className="region" aria-labelledby="period-totals-heading">
        <h2 id="period-totals-heading">Period totals</h2>
        {metrics === null ? <MetricsUnavailable /> : <PeriodTotalsPanel metrics={metrics} />}
      </section>
      <section className="region" aria-labelledby="tenants-heading">
        <h2 id="tenants-heading">Tenants</h2>
        <TenantsRegion tenants={tenants} />
      </section>
    </div>
  )
}
