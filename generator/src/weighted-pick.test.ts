import { describe, expect, it } from 'vitest';
import { createWeightedPicker, type RandomSource } from './weighted-pick.ts';

function seededRandom(seed: number): RandomSource {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

describe('createWeightedPicker', () => {
  it('distribution_follows_weights_within_two_points', () => {
    const pick = createWeightedPicker(
      [
        { id: 1, weight: 1 },
        { id: 2, weight: 3 },
      ],
      seededRandom(42),
    );
    const counts = new Map<number, number>();
    const picks = 40_000;
    for (let i = 0; i < picks; i++) {
      const id = pick();
      counts.set(id, (counts.get(id) ?? 0) + 1);
    }
    expect(Math.abs((counts.get(1) ?? 0) / picks - 0.25)).toBeLessThan(0.02);
    expect(Math.abs((counts.get(2) ?? 0) / picks - 0.75)).toBeLessThan(0.02);
    expect([...counts.keys()].sort()).toEqual([1, 2]);
  });

  it('random_zero_and_near_one_map_to_first_and_last', () => {
    const tenants = [
      { id: 7, weight: 2 },
      { id: 8, weight: 5 },
      { id: 9, weight: 1 },
    ];
    expect(createWeightedPicker(tenants, () => 0)()).toBe(7);
    expect(createWeightedPicker(tenants, () => 0.999999)()).toBe(9);
  });

  it('boundary_between_weights_goes_to_next_tenant', () => {
    const tenants = [
      { id: 7, weight: 1 },
      { id: 8, weight: 1 },
    ];
    expect(createWeightedPicker(tenants, () => 0.4999)()).toBe(7);
    expect(createWeightedPicker(tenants, () => 0.5)()).toBe(8);
  });

  it('single_tenant_is_always_picked', () => {
    const pick = createWeightedPicker([{ id: 4, weight: 0.1 }], seededRandom(1));
    expect(new Set(Array.from({ length: 100 }, pick))).toEqual(new Set([4]));
  });
});
