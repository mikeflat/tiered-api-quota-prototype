export function nextPollDelay(intervalMs: number, startedAt: number, finishedAt: number): number {
  const elapsed = finishedAt - startedAt
  return Math.max(0, intervalMs - elapsed)
}
