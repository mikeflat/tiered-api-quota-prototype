export function usagePercent(used: number | null, limit: number): number | null {
  if (used === null || limit <= 0) {
    return null
  }
  return Math.floor((used * 100) / limit)
}
