export type TenantStatus = 'ok' | 'near' | 'over'

export interface QuotaPeriod {
  readonly index: number
  readonly endsAt: string
  readonly secondsLeft: number
}

export interface SecondTraffic {
  readonly second: number
  readonly allowed: number
  readonly rejected: number
}

export interface PeriodTotals {
  readonly allowed: number
  readonly rejected: number
}

export interface TrafficMetrics {
  readonly perSecond: readonly SecondTraffic[]
  readonly totals: PeriodTotals
  readonly tenantsNear: number
  readonly tenantsOver: number
}

export interface TenantRow {
  readonly id: number
  readonly name: string
  readonly tier: string
  readonly limit: number
  readonly used: number | null
  readonly rejected: number | null
  readonly status: TenantStatus | null
}

export interface TenantPage {
  readonly rowsAvailable: boolean
  readonly rows: readonly TenantRow[] | null
  readonly total: number | null
  readonly page: number
  readonly size: number
}

export interface AdminSnapshot {
  readonly period: QuotaPeriod
  readonly metrics: TrafficMetrics | null
  readonly tenants: TenantPage
}
