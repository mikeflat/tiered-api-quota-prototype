import { MetricsUnavailable } from './MetricsUnavailable.tsx'

interface DataRegionsProps {
  readonly stale: boolean
  readonly metricsAvailable: boolean
  readonly describedBy?: string
}

export function DataRegions({ stale, metricsAvailable, describedBy }: DataRegionsProps) {
  return (
    <div className={stale ? 'data-regions is-stale' : 'data-regions'} data-testid="data-regions" aria-describedby={describedBy}>
      <section className="region" aria-labelledby="traffic-heading">
        <h2 id="traffic-heading">Traffic</h2>
        {!metricsAvailable && <MetricsUnavailable />}
      </section>
      <section className="region" aria-labelledby="period-totals-heading">
        <h2 id="period-totals-heading">Period totals</h2>
        {!metricsAvailable && <MetricsUnavailable />}
      </section>
      <section className="region" aria-labelledby="tenants-heading">
        <h2 id="tenants-heading">Tenants</h2>
      </section>
    </div>
  )
}
