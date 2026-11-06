import { describe, expect, it, vi } from 'vitest'
import { snapshotWith, snapshotWithoutMetrics } from '../../support/snapshots.ts'
import { createHttpSnapshotSource, SnapshotRequestFailed } from '../../../src/adapters/api/httpSnapshotSource.ts'
import { initialTenantQuery, noFilters, type TenantQuery } from '../../../src/domain/tenantQuery.ts'
import { UnexpectedResponseBody } from '../../../src/adapters/api/json.ts'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function sourceAnswering(response: Response | Promise<Response>) {
  const fetchFn = vi.fn<typeof fetch>(() => Promise.resolve(response))
  return { fetchFn, source: createHttpSnapshotSource(fetchFn) }
}

const signal = new AbortController().signal

describe('httpSnapshotSource', () => {
  it('requests_admin_snapshot_with_signal', async () => {
    const { fetchFn, source } = sourceAnswering(jsonResponse(snapshotWith()))

    await source.fetchSnapshot(initialTenantQuery, signal)

    expect(fetchFn).toHaveBeenCalledWith('/admin/snapshot?page=0&size=10', {
      signal,
      headers: { Accept: 'application/json' },
    })
  })

  it('valid_body_maps_to_snapshot', async () => {
    const snapshot = snapshotWith()
    const { source } = sourceAnswering(jsonResponse(snapshot))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).resolves.toEqual(snapshot)
  })

  it('null_metrics_are_kept_null', async () => {
    const { source } = sourceAnswering(jsonResponse(snapshotWithoutMetrics()))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).resolves.toMatchObject({ metrics: null })
  })

  it('unavailable_rows_are_kept_null', async () => {
    const body = snapshotWith({
      tenants: { rowsAvailable: false, rows: null, total: null, page: 0, size: 10 },
    })
    const { source } = sourceAnswering(jsonResponse(body))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).resolves.toEqual(body)
  })

  it('server_error_rejects', async () => {
    const { source } = sourceAnswering(new Response('<html>Bad Gateway</html>', { status: 502 }))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).rejects.toBeInstanceOf(SnapshotRequestFailed)
  })

  it('client_error_rejects', async () => {
    const { source } = sourceAnswering(jsonResponse({ title: 'Bad Request' }, 400))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).rejects.toBeInstanceOf(SnapshotRequestFailed)
  })

  it('non_json_body_rejects', async () => {
    const { source } = sourceAnswering(new Response('<!doctype html>', { status: 200 }))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('body_without_period_rejects', async () => {
    const { metrics, tenants } = snapshotWith()
    const { source } = sourceAnswering(jsonResponse({ metrics, tenants }))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('body_without_tenants_rejects', async () => {
    const { period, metrics } = snapshotWith()
    const { source } = sourceAnswering(jsonResponse({ period, metrics }))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('network_error_rejects', async () => {
    const { source } = sourceAnswering(Promise.reject(new TypeError('Failed to fetch')))

    await expect(source.fetchSnapshot(initialTenantQuery, signal)).rejects.toBeInstanceOf(TypeError)
  })

  it('aborted_request_rejects', async () => {
    const controller = new AbortController()
    const fetchFn = vi.fn<typeof fetch>(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
        }),
    )
    const pending = createHttpSnapshotSource(fetchFn).fetchSnapshot(initialTenantQuery, controller.signal)

    controller.abort()

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('sends_set_filters_in_order', async () => {
    const { fetchFn, source } = sourceAnswering(jsonResponse(snapshotWith()))
    const query: TenantQuery = { filters: { name: 'co', id: 3, tier: 'Bronze', status: 'over' }, page: 1 }

    await source.fetchSnapshot(query, signal)

    expect(fetchFn.mock.calls[0]?.[0]).toBe('/admin/snapshot?name=co&id=3&tier=Bronze&status=over&page=1&size=10')
  })

  it('omits_empty_filters', async () => {
    const { fetchFn, source } = sourceAnswering(jsonResponse(snapshotWith()))

    await source.fetchSnapshot({ filters: { ...noFilters, tier: 'Gold' }, page: 0 }, signal)

    expect(fetchFn.mock.calls[0]?.[0]).toBe('/admin/snapshot?tier=Gold&page=0&size=10')
  })

  it('encodes_name', async () => {
    const { fetchFn, source } = sourceAnswering(jsonResponse(snapshotWith()))

    await source.fetchSnapshot({ filters: { ...noFilters, name: 'co & sons' }, page: 0 }, signal)

    expect(fetchFn.mock.calls[0]?.[0]).toBe('/admin/snapshot?name=co+%26+sons&page=0&size=10')
  })

  it('sends_page_and_size_always', async () => {
    const { fetchFn, source } = sourceAnswering(jsonResponse(snapshotWith()))

    await source.fetchSnapshot({ filters: noFilters, page: 4 }, signal)

    expect(fetchFn.mock.calls[0]?.[0]).toBe('/admin/snapshot?page=4&size=10')
  })
})
