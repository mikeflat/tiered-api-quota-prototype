import type { WeightedTenant } from './traffic-config.ts';

export type RandomSource = () => number;

export function createWeightedPicker(tenants: readonly WeightedTenant[], random: RandomSource): () => number {
  const ids = tenants.map((tenant) => tenant.id);
  const cumulativeWeights: number[] = [];
  let total = 0;
  for (const tenant of tenants) {
    total += tenant.weight;
    cumulativeWeights.push(total);
  }
  return () => ids[firstIndexAbove(cumulativeWeights, random() * total)]!;
}

function firstIndexAbove(cumulativeWeights: readonly number[], target: number): number {
  let low = 0;
  let high = cumulativeWeights.length - 1;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (cumulativeWeights[middle]! > target) {
      high = middle;
    } else {
      low = middle + 1;
    }
  }
  return low;
}
