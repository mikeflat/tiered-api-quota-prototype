import type { HealthSource } from '../../application/ports.ts'
import type { HealthReport } from '../../domain/enforcement.ts'
import { asObject, isObject, readJson } from './json.ts'

export const healthPath = '/actuator/health'

const statusesWithHealthBody = new Set([200, 503])

export class HealthRequestFailed extends Error {
  constructor(status: number) {
    super(`Health request failed with status ${status}`)
    this.name = 'HealthRequestFailed'
  }
}

export function createHttpHealthSource(fetchFn: typeof fetch): HealthSource {
  return {
    async fetchHealth(signal) {
      const response = await fetchFn(healthPath, { signal, headers: { Accept: 'application/json' } })
      if (!statusesWithHealthBody.has(response.status)) {
        throw new HealthRequestFailed(response.status)
      }
      return toHealthReport(await readJson(response))
    },
  }
}

function toHealthReport(json: unknown): HealthReport {
  const body = asObject(json, 'health')
  const redis = isObject(body.components) ? body.components.redis : undefined
  const redisStatus = isObject(redis) && typeof redis.status === 'string' ? redis.status : null
  return { redisStatus }
}
