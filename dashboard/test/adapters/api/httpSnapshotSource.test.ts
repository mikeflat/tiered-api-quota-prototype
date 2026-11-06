import { describe, expect, it, vi } from 'vitest'
import { snapshotWith, snapshotWithoutMetrics } from '../../support/snapshots.ts'
import { createHttpSnapshotSource, SnapshotRequestFailed } from '../../../src/adapters/api/httpSnapshotSource.ts'
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

    await source.fetchSnapshot(signal)

    expect(fetchFn).toHaveBeenCalledWith('/admin/snapshot', {
      signal,
      headers: { Accept: 'application/json' },
    })
  })

  it('valid_body_maps_to_snapshot', async () => {
    const snapshot = snapshotWith()
    const { source } = sourceAnswering(jsonResponse(snapshot))

    await expect(source.fetchSnapshot(signal)).resolves.toEqual(snapshot)
  })

  it('null_metrics_are_kept_null', async () => {
    const { source } = sourceAnswering(jsonResponse(snapshotWithoutMetrics()))

    await expect(source.fetchSnapshot(signal)).resolves.toMatchObject({ metrics: null })
  })

  it('unavailable_rows_are_kept_null', async () => {
    const body = snapshotWith({
      tenants: { rowsAvailable: false, rows: null, total: null, page: 0, size: 10 },
    })
    const { source } = sourceAnswering(jsonResponse(body))

    await expect(source.fetchSnapshot(signal)).resolves.toEqual(body)
  })

  it('server_error_rejects', async () => {
    const { source } = sourceAnswering(new Response('<html>Bad Gateway</html>', { status: 502 }))

    await expect(source.fetchSnapshot(signal)).rejects.toBeInstanceOf(SnapshotRequestFailed)
  })

  it('client_error_rejects', async () => {
    const { source } = sourceAnswering(jsonResponse({ title: 'Bad Request' }, 400))

    await expect(source.fetchSnapshot(signal)).rejects.toBeInstanceOf(SnapshotRequestFailed)
  })

  it('non_json_body_rejects', async () => {
    const { source } = sourceAnswering(new Response('<!doctype html>', { status: 200 }))

    await expect(source.fetchSnapshot(signal)).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('body_without_period_rejects', async () => {
    const { metrics, tenants } = snapshotWith()
    const { source } = sourceAnswering(jsonResponse({ metrics, tenants }))

    await expect(source.fetchSnapshot(signal)).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('body_without_tenants_rejects', async () => {
    const { period, metrics } = snapshotWith()
    const { source } = sourceAnswering(jsonResponse({ period, metrics }))

    await expect(source.fetchSnapshot(signal)).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('network_error_rejects', async () => {
    const { source } = sourceAnswering(Promise.reject(new TypeError('Failed to fetch')))

    await expect(source.fetchSnapshot(signal)).rejects.toBeInstanceOf(TypeError)
  })

  it('aborted_request_rejects', async () => {
    const controller = new AbortController()
    const fetchFn = vi.fn<typeof fetch>(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
        }),
    )
    const pending = createHttpSnapshotSource(fetchFn).fetchSnapshot(controller.signal)

    controller.abort()

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })
})
