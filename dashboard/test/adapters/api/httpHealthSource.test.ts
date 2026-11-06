import { describe, expect, it, vi } from 'vitest'
import { createHttpHealthSource, HealthRequestFailed } from '../../../src/adapters/api/httpHealthSource.ts'
import { UnexpectedResponseBody } from '../../../src/adapters/api/json.ts'

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function sourceAnswering(response: Response | Promise<Response>) {
  const fetchFn = vi.fn<typeof fetch>(() => Promise.resolve(response))
  return { fetchFn, source: createHttpHealthSource(fetchFn) }
}

const signal = new AbortController().signal

describe('httpHealthSource', () => {
  it('requests_actuator_health_with_signal', async () => {
    const { fetchFn, source } = sourceAnswering(jsonResponse({ status: 'UP' }, 200))

    await source.fetchHealth(signal)

    expect(fetchFn).toHaveBeenCalledWith('/actuator/health', { signal, headers: { Accept: 'application/json' } })
  })

  it('redis_up_is_reported', async () => {
    const { source } = sourceAnswering(jsonResponse({ status: 'UP', components: { redis: { status: 'UP' } } }, 200))

    await expect(source.fetchHealth(signal)).resolves.toEqual({ redisStatus: 'UP' })
  })

  it('redis_down_with_503_is_reported_not_rejected', async () => {
    const { source } = sourceAnswering(
      jsonResponse({ status: 'DOWN', components: { redis: { status: 'DOWN' } } }, 503),
    )

    await expect(source.fetchHealth(signal)).resolves.toEqual({ redisStatus: 'DOWN' })
  })

  it('missing_redis_component_reports_null', async () => {
    const { source } = sourceAnswering(jsonResponse({ status: 'UP' }, 200))

    await expect(source.fetchHealth(signal)).resolves.toEqual({ redisStatus: null })
  })

  it('bad_gateway_html_rejects', async () => {
    const { source } = sourceAnswering(new Response('<html>502 Bad Gateway</html>', { status: 502 }))

    await expect(source.fetchHealth(signal)).rejects.toBeInstanceOf(HealthRequestFailed)
  })

  it('service_unavailable_without_json_rejects', async () => {
    const { source } = sourceAnswering(new Response('<html>503</html>', { status: 503 }))

    await expect(source.fetchHealth(signal)).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('network_error_rejects', async () => {
    const { source } = sourceAnswering(Promise.reject(new TypeError('Failed to fetch')))

    await expect(source.fetchHealth(signal)).rejects.toBeInstanceOf(TypeError)
  })
})
