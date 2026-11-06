import type { TierAdministration } from '../../src/application/ports.ts'
import type { ChangedTier, TierOption } from '../../src/domain/tiers.ts'
import { type Deferred, deferred } from './deferred.ts'

export interface TierListRequest {
  readonly signal: AbortSignal
  readonly reply: Deferred<readonly TierOption[]>
}

export interface TierChangeCall {
  readonly tenantId: number
  readonly tier: string
  readonly signal: AbortSignal
  readonly reply: Deferred<ChangedTier>
}

export class FakeTierAdministration implements TierAdministration {
  readonly tierRequests: TierListRequest[] = []
  readonly changes: TierChangeCall[] = []

  fetchTiers(signal: AbortSignal): Promise<readonly TierOption[]> {
    const reply = deferred<readonly TierOption[]>()
    this.tierRequests.push({ signal, reply })
    return reply.promise
  }

  changeTier(tenantId: number, tier: string, signal: AbortSignal): Promise<ChangedTier> {
    const reply = deferred<ChangedTier>()
    this.changes.push({ tenantId, tier, signal, reply })
    return reply.promise
  }

  answerTiers(tiers: readonly TierOption[]): void {
    lastOf(this.tierRequests, 'tier list').reply.resolve(tiers)
  }

  failTiers(): void {
    lastOf(this.tierRequests, 'tier list').reply.reject(new TypeError('Failed to fetch'))
  }

  latestChange(): TierChangeCall {
    return lastOf(this.changes, 'tier change')
  }

  answerChange(changed: ChangedTier): void {
    this.latestChange().reply.resolve(changed)
  }

  failChange(error: unknown): void {
    this.latestChange().reply.reject(error)
  }
}

function lastOf<T>(items: readonly T[], what: string): T {
  const last = items.at(-1)
  if (last === undefined) {
    throw new Error(`no ${what} request was sent`)
  }
  return last
}
