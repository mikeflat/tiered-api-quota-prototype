import type { HealthSource, SnapshotSource } from '../../src/application/ports.ts'
import type { HealthReport } from '../../src/domain/enforcement.ts'
import type { AdminSnapshot } from '../../src/domain/snapshot.ts'
import type { TenantQuery } from '../../src/domain/tenantQuery.ts'
import { type Deferred, deferred } from './deferred.ts'

interface Request<T> {
  readonly signal: AbortSignal
  readonly reply: Deferred<T>
}

class FakeSource<T> {
  readonly requests: Request<T>[] = []

  request(signal: AbortSignal): Promise<T> {
    const reply = deferred<T>()
    this.requests.push({ signal, reply })
    return reply.promise
  }

  requestCount(): number {
    return this.requests.length
  }

  answerLatest(value: T): void {
    this.latest().reply.resolve(value)
  }

  failLatest(): void {
    this.latest().reply.reject(new TypeError('Failed to fetch'))
  }

  latest(): Request<T> {
    const latest = this.requests.at(-1)
    if (latest === undefined) {
      throw new Error('no request was sent')
    }
    return latest
  }
}

export class FakeSnapshotSource extends FakeSource<AdminSnapshot> implements SnapshotSource {
  readonly queries: TenantQuery[] = []

  fetchSnapshot(query: TenantQuery, signal: AbortSignal): Promise<AdminSnapshot> {
    this.queries.push(query)
    return this.request(signal)
  }

  latestQuery(): TenantQuery {
    const latest = this.queries.at(-1)
    if (latest === undefined) {
      throw new Error('no snapshot request was sent')
    }
    return latest
  }
}

export class FakeHealthSource extends FakeSource<HealthReport> implements HealthSource {
  fetchHealth(signal: AbortSignal): Promise<HealthReport> {
    return this.request(signal)
  }
}

export const redisUp: HealthReport = { redisStatus: 'UP' }
export const redisDown: HealthReport = { redisStatus: 'DOWN' }
