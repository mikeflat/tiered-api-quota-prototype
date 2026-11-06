import type { SnapshotSource } from '../../application/ports.ts'
import type {
  AdminSnapshot,
  QuotaPeriod,
  SecondTraffic,
  TenantPage,
  TenantRow,
  TenantStatus,
  TrafficMetrics,
} from '../../domain/snapshot.ts'
import { tenantPageSize, type TenantQuery } from '../../domain/tenantQuery.ts'
import {
  arrayAt,
  asObject,
  booleanAt,
  type JsonObject,
  nullableNumberAt,
  numberAt,
  objectAt,
  readJson,
  stringAt,
  UnexpectedResponseBody,
} from './json.ts'

export const snapshotPath = '/admin/snapshot'

export class SnapshotRequestFailed extends Error {
  constructor(status: number) {
    super(`Snapshot request failed with status ${status}`)
    this.name = 'SnapshotRequestFailed'
  }
}

export function createHttpSnapshotSource(fetchFn: typeof fetch): SnapshotSource {
  return {
    async fetchSnapshot(query, signal) {
      const response = await fetchFn(snapshotUrlOf(query), { signal, headers: { Accept: 'application/json' } })
      if (!response.ok) {
        throw new SnapshotRequestFailed(response.status)
      }
      return toAdminSnapshot(await readJson(response))
    },
  }
}

export function snapshotUrlOf(query: TenantQuery): string {
  const { name, id, tier, status } = query.filters
  const parameters = new URLSearchParams()
  if (name !== '') {
    parameters.set('name', name)
  }
  if (id !== null) {
    parameters.set('id', String(id))
  }
  if (tier !== null) {
    parameters.set('tier', tier)
  }
  if (status !== null) {
    parameters.set('status', status)
  }
  parameters.set('page', String(query.page))
  parameters.set('size', String(tenantPageSize))
  return `${snapshotPath}?${parameters.toString()}`
}

export function toAdminSnapshot(json: unknown): AdminSnapshot {
  const body = asObject(json, 'snapshot')
  return {
    period: toPeriod(objectAt(body, 'period')),
    metrics: body.metrics === null ? null : toMetrics(objectAt(body, 'metrics')),
    tenants: toTenantPage(objectAt(body, 'tenants')),
  }
}

function toPeriod(period: JsonObject): QuotaPeriod {
  return {
    index: numberAt(period, 'index'),
    endsAt: stringAt(period, 'endsAt'),
    secondsLeft: numberAt(period, 'secondsLeft'),
  }
}

function toMetrics(metrics: JsonObject): TrafficMetrics {
  const totals = objectAt(metrics, 'totals')
  return {
    perSecond: arrayAt(metrics, 'perSecond').map(toSecondTraffic),
    totals: { allowed: numberAt(totals, 'allowed'), rejected: numberAt(totals, 'rejected') },
    tenantsNear: numberAt(metrics, 'tenantsNear'),
    tenantsOver: numberAt(metrics, 'tenantsOver'),
  }
}

function toSecondTraffic(entry: unknown): SecondTraffic {
  const second = asObject(entry, 'perSecond entry')
  return {
    second: numberAt(second, 'second'),
    allowed: numberAt(second, 'allowed'),
    rejected: numberAt(second, 'rejected'),
  }
}

function toTenantPage(tenants: JsonObject): TenantPage {
  return {
    rowsAvailable: booleanAt(tenants, 'rowsAvailable'),
    rows: tenants.rows === null ? null : arrayAt(tenants, 'rows').map(toTenantRow),
    total: nullableNumberAt(tenants, 'total'),
    page: numberAt(tenants, 'page'),
    size: numberAt(tenants, 'size'),
  }
}

function toTenantRow(entry: unknown): TenantRow {
  const row = asObject(entry, 'tenant row')
  return {
    id: numberAt(row, 'id'),
    name: stringAt(row, 'name'),
    tier: stringAt(row, 'tier'),
    limit: numberAt(row, 'limit'),
    used: nullableNumberAt(row, 'used'),
    rejected: nullableNumberAt(row, 'rejected'),
    status: toStatus(row.status),
  }
}

function toStatus(value: unknown): TenantStatus | null {
  if (value === null || value === 'ok' || value === 'near' || value === 'over') {
    return value
  }
  throw new UnexpectedResponseBody('status is not ok, near, over or null')
}
