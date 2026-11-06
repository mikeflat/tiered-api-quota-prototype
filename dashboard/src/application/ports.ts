import type { HealthReport } from '../domain/enforcement.ts'
import type { AdminSnapshot } from '../domain/snapshot.ts'

export interface SnapshotSource {
  fetchSnapshot(signal: AbortSignal): Promise<AdminSnapshot>
}

export interface HealthSource {
  fetchHealth(signal: AbortSignal): Promise<HealthReport>
}

export type Cancel = () => void

export interface Scheduler {
  now(): number
  schedule(callback: () => void, delayMs: number): Cancel
}
