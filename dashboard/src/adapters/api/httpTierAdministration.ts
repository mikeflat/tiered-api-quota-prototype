import type { TierAdministration } from '../../application/ports.ts'
import { TierChangeRefused } from '../../domain/tierChange.ts'
import type { ChangedTier, TierOption } from '../../domain/tiers.ts'
import { asObject, numberAt, readJson, stringAt, UnexpectedResponseBody } from './json.ts'

export const tiersPath = '/admin/tiers'

export function tierChangePath(tenantId: number): string {
  return `/admin/tenants/${tenantId}/tier`
}

export class TierListRequestFailed extends Error {
  constructor(status: number) {
    super(`Tier list request failed with status ${status}`)
    this.name = 'TierListRequestFailed'
  }
}

export function createHttpTierAdministration(fetchFn: typeof fetch): TierAdministration {
  return {
    async fetchTiers(signal) {
      const response = await fetchFn(tiersPath, { signal, headers: { Accept: 'application/json' } })
      if (!response.ok) {
        throw new TierListRequestFailed(response.status)
      }
      return toTierOptions(await readJson(response))
    },

    async changeTier(tenantId, tier, signal) {
      const response = await fetchFn(tierChangePath(tenantId), {
        method: 'PUT',
        signal,
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify({ tier }),
      })
      if (!response.ok) {
        throw new TierChangeRefused(response.status)
      }
      return toChangedTier(await readJson(response))
    },
  }
}

function toTierOptions(json: unknown): TierOption[] {
  if (!Array.isArray(json)) {
    throw new UnexpectedResponseBody('tier list is not an array')
  }
  return json.map((entry) => {
    const tier = asObject(entry, 'tier')
    return { name: stringAt(tier, 'name'), limit: numberAt(tier, 'limit') }
  })
}

function toChangedTier(json: unknown): ChangedTier {
  const body = asObject(json, 'tenant tier')
  return {
    id: numberAt(body, 'id'),
    name: stringAt(body, 'name'),
    tier: stringAt(body, 'tier'),
    limit: numberAt(body, 'limit'),
  }
}
