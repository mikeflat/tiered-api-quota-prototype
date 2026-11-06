export type EnforcementStatus = 'ok' | 'degraded' | 'unknown'

export interface HealthReport {
  readonly redisStatus: string | null
}

export function enforcementStatusOf(report: HealthReport): EnforcementStatus {
  if (report.redisStatus === 'DOWN') {
    return 'degraded'
  }
  if (report.redisStatus === 'UP') {
    return 'ok'
  }
  return 'unknown'
}
