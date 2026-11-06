import { describe, expect, it, vi } from 'vitest'
import { createHttpTierAdministration, TierListRequestFailed } from '../../../src/adapters/api/httpTierAdministration.ts'
import { UnexpectedResponseBody } from '../../../src/adapters/api/json.ts'
import { TierChangeRefused } from '../../../src/domain/tierChange.ts'
import { changedTier, defaultTiers } from '../../support/tiers.ts'

function jsonResponse(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } })
}

function problem(status: number): Response {
  return jsonResponse({ title: 'Problem', status }, status, 'application/problem+json')
}

function administrationAnswering(response: Response) {
  const fetchFn = vi.fn<typeof fetch>(() => Promise.resolve(response))
  return { fetchFn, administration: createHttpTierAdministration(fetchFn) }
}

const signal = new AbortController().signal

describe('httpTierAdministration', () => {
  it('fetches_admin_tiers_with_signal', async () => {
    const { fetchFn, administration } = administrationAnswering(jsonResponse(defaultTiers))

    await administration.fetchTiers(signal)

    expect(fetchFn).toHaveBeenCalledWith('/admin/tiers', { signal, headers: { Accept: 'application/json' } })
  })

  it('maps_tier_list', async () => {
    const { administration } = administrationAnswering(jsonResponse(defaultTiers))

    await expect(administration.fetchTiers(signal)).resolves.toEqual(defaultTiers)
  })

  it('tier_list_error_status_rejects', async () => {
    const { administration } = administrationAnswering(new Response('Bad Gateway', { status: 502 }))

    await expect(administration.fetchTiers(signal)).rejects.toBeInstanceOf(TierListRequestFailed)
  })

  it('invalid_tier_list_rejects', async () => {
    await expect(administrationAnswering(jsonResponse({ tiers: [] })).administration.fetchTiers(signal)).rejects.toBeInstanceOf(
      UnexpectedResponseBody,
    )
    await expect(
      administrationAnswering(jsonResponse([{ name: 'Bronze' }])).administration.fetchTiers(signal),
    ).rejects.toBeInstanceOf(UnexpectedResponseBody)
  })

  it('puts_tier_as_json_to_tenant_path', async () => {
    const { fetchFn, administration } = administrationAnswering(jsonResponse(changedTier()))

    await administration.changeTier(1, 'Gold', signal)

    expect(fetchFn).toHaveBeenCalledWith('/admin/tenants/1/tier', {
      method: 'PUT',
      signal,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: '{"tier":"Gold"}',
    })
  })

  it('maps_changed_tier', async () => {
    const { administration } = administrationAnswering(jsonResponse(changedTier()))

    await expect(administration.changeTier(1, 'Gold', signal)).resolves.toEqual({
      id: 1,
      name: 'Acme Corp',
      tier: 'Gold',
      limit: 10000,
    })
  })

  it('not_found_rejects_with_refused_404', async () => {
    await expect(administrationAnswering(problem(404)).administration.changeTier(99, 'Gold', signal)).rejects.toEqual(
      new TierChangeRefused(404),
    )
  })

  it('bad_request_rejects_with_refused_400', async () => {
    const rejection = administrationAnswering(problem(400)).administration.changeTier(1, 'Platinum', signal)

    await expect(rejection).rejects.toBeInstanceOf(TierChangeRefused)
    await expect(rejection).rejects.toMatchObject({ status: 400 })
  })

  it('bad_gateway_rejects_with_refused_502', async () => {
    const rejection = administrationAnswering(new Response('<html>Bad Gateway</html>', { status: 502 })).administration.changeTier(
      1,
      'Gold',
      signal,
    )

    await expect(rejection).rejects.toMatchObject({ name: 'TierChangeRefused', status: 502 })
  })

  it('network_error_rejects_unchanged', async () => {
    const networkError = new TypeError('Failed to fetch')
    const administration = createHttpTierAdministration(() => Promise.reject(networkError))

    await expect(administration.changeTier(1, 'Gold', signal)).rejects.toBe(networkError)
  })
})
