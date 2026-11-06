import { describe, expect, it } from 'vitest';
import { createPacer } from './pacing.ts';

function releasedOver(pacer: ReturnType<typeof createPacer>, fromMs: number, toMs: number, tickMs: number): number {
  let released = 0;
  for (let now = fromMs; now <= toMs; now += tickMs) {
    released += pacer.takeDue(now);
  }
  return released;
}

describe('createPacer', () => {
  it('releases_rate_times_elapsed_seconds', () => {
    const pacer = createPacer(200, 1_000);
    expect(releasedOver(pacer, 1_020, 11_000, 20)).toBe(2000);
  });

  it('holds_rate_with_irregular_ticks', () => {
    const pacer = createPacer(200, 0);
    let released = 0;
    let now = 0;
    const ticks = [15, 16, 31, 1, 47, 16, 15];
    for (let i = 0; now < 10_000; i++) {
      now = Math.min(10_000, now + ticks[i % ticks.length]!);
      released += pacer.takeDue(now);
    }
    expect(released).toBe(2000);
  });

  it('handles_fractional_rates_without_drift', () => {
    const pacer = createPacer(2.5, 0);
    expect(releasedOver(pacer, 20, 4_000, 20)).toBe(10);
  });

  it('same_instant_releases_nothing_twice', () => {
    const pacer = createPacer(100, 0);
    expect(pacer.takeDue(50)).toBe(5);
    expect(pacer.takeDue(50)).toBe(0);
  });

  it('clock_going_backwards_releases_nothing', () => {
    const pacer = createPacer(100, 1_000);
    expect(pacer.takeDue(500)).toBe(0);
  });

  it('catch_up_after_stall_is_capped_at_one_second', () => {
    const pacer = createPacer(200, 0);
    expect(pacer.takeDue(5_000)).toBe(200);
  });

  it('pacing_continues_at_rate_after_stall', () => {
    const pacer = createPacer(200, 0);
    pacer.takeDue(5_000);
    expect(pacer.takeDue(5_000)).toBe(0);
    expect(releasedOver(pacer, 5_020, 6_000, 20)).toBe(200);
  });
});
