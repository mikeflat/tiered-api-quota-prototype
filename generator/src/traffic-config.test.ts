import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { readFilePaths } from './config.ts';
import { loadTenantIds } from './tenants.ts';
import { loadTrafficConfig, parseTrafficConfig, type TrafficConfig } from './traffic-config.ts';

const PATH = '/app/traffic.json';
const IDS = [1, 2, 3];

function parse(config: object, ids: readonly number[] = IDS): TrafficConfig {
  return parseTrafficConfig(JSON.stringify(config), PATH, ids);
}

describe('parseTrafficConfig', () => {
  it('optional_fields_take_defaults', () => {
    expect(parse({ ratePerSecond: 50 })).toEqual({
      ratePerSecond: 50,
      maxInFlight: 100,
      requestTimeoutMs: 2000,
      weightedTenants: [
        { id: 1, weight: 1 },
        { id: 2, weight: 1 },
        { id: 3, weight: 1 },
      ],
    });
  });

  it('unlisted_tenants_get_default_weight', () => {
    expect(parse({ ratePerSecond: 10, defaultWeight: 2, weights: { '2': 7 } }).weightedTenants).toEqual([
      { id: 1, weight: 2 },
      { id: 2, weight: 7 },
      { id: 3, weight: 2 },
    ]);
  });

  it('zero_weight_tenants_are_dropped', () => {
    expect(parse({ ratePerSecond: 10, defaultWeight: 0, weights: { '3': 1 } }).weightedTenants).toEqual([
      { id: 3, weight: 1 },
    ]);
  });

  it.each([undefined, 0, -1, 5001, '200', null])('rate_must_be_positive_and_at_most_5000 (%s)', (rate) => {
    expect(() => parse({ ratePerSecond: rate })).toThrow('"ratePerSecond"');
  });

  it('rate_of_5000_and_fractional_rates_are_accepted', () => {
    expect(parse({ ratePerSecond: 5000 }).ratePerSecond).toBe(5000);
    expect(parse({ ratePerSecond: 0.5 }).ratePerSecond).toBe(0.5);
  });

  it.each([-1, 'heavy', null])('negative_or_non_numeric_weight_is_rejected (%s)', (weight) => {
    expect(() => parse({ ratePerSecond: 10, weights: { '1': weight } })).toThrow('"weights.1" must be a number >= 0');
  });

  it('negative_default_weight_is_rejected', () => {
    expect(() => parse({ ratePerSecond: 10, defaultWeight: -2 })).toThrow('"defaultWeight" must be a number >= 0');
  });

  it('weights_must_be_an_object', () => {
    expect(() => parse({ ratePerSecond: 10, weights: [1, 2] })).toThrow('"weights" must be an object');
  });

  it('unknown_key_is_rejected', () => {
    expect(() => parse({ ratePerSecond: 10, rate: 5 })).toThrow('unknown key "rate"');
  });

  it.each(['99', '01', 'x'])('weight_for_unknown_tenant_names_the_id (%s)', (key) => {
    expect(() => parse({ ratePerSecond: 10, weights: { [key]: 1 } })).toThrow(
      `tenant id "${key}" that is not in the tenants file`,
    );
  });

  it('all_zero_weights_are_rejected', () => {
    expect(() => parse({ ratePerSecond: 10, defaultWeight: 0 })).toThrow('every tenant has weight 0');
  });

  it.each([
    ['maxInFlight', 0],
    ['maxInFlight', 2.5],
    ['requestTimeoutMs', -5],
    ['requestTimeoutMs', '2000'],
  ])('invalid_integer_limits_are_rejected (%s=%s)', (field, value) => {
    expect(() => parse({ ratePerSecond: 10, [field]: value })).toThrow(`"${field}" must be an integer >= 1`);
  });

  it('errors_name_path_and_field', () => {
    expect(() => parse({ ratePerSecond: 0 })).toThrow(`traffic config ${PATH}: "ratePerSecond"`);
    expect(() => parseTrafficConfig('{', PATH, IDS)).toThrow(`traffic config ${PATH}: not valid JSON`);
    expect(() => parseTrafficConfig('[]', PATH, IDS)).toThrow(`traffic config ${PATH}: expected a JSON object`);
  });

  it('missing_file_names_path', () => {
    const missing = join(mkdtempSync(join(tmpdir(), 'traffic-')), 'absent.json');
    expect(() => loadTrafficConfig(missing, IDS)).toThrow(`traffic config ${missing}: cannot read file (ENOENT)`);
  });
});

describe('default shape', () => {
  const PERIOD_SECONDS = 60;
  const NEAR_RATIO = 0.8;
  const LIMIT_BY_TIER: Readonly<Record<string, number>> = { Bronze: 100, Silver: 1000, Gold: 10000 };
  const SEEDED_TIERS: Readonly<Record<number, string>> = {
    1: 'Bronze',
    2: 'Bronze',
    3: 'Bronze',
    4: 'Bronze',
    5: 'Bronze',
    6: 'Bronze',
    7: 'Bronze',
    8: 'Bronze',
    9: 'Silver',
    10: 'Silver',
    11: 'Silver',
    12: 'Silver',
    13: 'Silver',
    14: 'Gold',
    15: 'Gold',
  };

  function expectedStates(config: TrafficConfig): Set<string> {
    const totalWeight = config.weightedTenants.reduce((sum, tenant) => sum + tenant.weight, 0);
    return new Set(
      config.weightedTenants.map((tenant) => {
        const expectedPerPeriod = (config.ratePerSecond * PERIOD_SECONDS * tenant.weight) / totalWeight;
        const limit = LIMIT_BY_TIER[SEEDED_TIERS[tenant.id] ?? ''] ?? 0;
        if (expectedPerPeriod >= limit) return 'over';
        if (expectedPerPeriod >= limit * NEAR_RATIO) return 'near';
        return 'ok';
      }),
    );
  }

  it('default_shape_puts_tenants_in_ok_near_and_over', () => {
    const paths = readFilePaths({});
    const config = loadTrafficConfig(paths.trafficConfigFile, loadTenantIds(paths.tenantsFile));
    expect(config.ratePerSecond).toBe(200);
    expect(expectedStates(config)).toEqual(new Set(['ok', 'near', 'over']));
  });

  it('shape_with_every_tenant_under_limit_fails_the_three_state_check', () => {
    const seededIds = Object.keys(SEEDED_TIERS).map(Number);
    expect(expectedStates(parse({ ratePerSecond: 10 }, seededIds))).toEqual(new Set(['ok']));
  });
});
