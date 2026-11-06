const MS_PER_SECOND = 1000;

export type Pacer = {
  takeDue(nowMs: number): number;
};

export function createPacer(ratePerSecond: number, startMs: number): Pacer {
  const maxBurst = Math.max(1, Math.ceil(ratePerSecond));
  let baselineMs = startMs;
  let releasedSinceBaseline = 0;

  const scheduledSinceBaseline = (nowMs: number): number =>
    Math.floor(((nowMs - baselineMs) * ratePerSecond) / MS_PER_SECOND);

  return {
    takeDue(nowMs: number): number {
      const due = scheduledSinceBaseline(nowMs) - releasedSinceBaseline;
      if (due <= 0) {
        return 0;
      }
      if (due > maxBurst) {
        baselineMs = nowMs;
        releasedSinceBaseline = 0;
        return maxBurst;
      }
      releasedSinceBaseline += due;
      return due;
    },
  };
}
