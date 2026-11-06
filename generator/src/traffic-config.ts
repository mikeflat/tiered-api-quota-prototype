import { readFileSync } from 'node:fs';

const MAX_RATE_PER_SECOND = 5000;
const DEFAULT_WEIGHT = 1;
const DEFAULT_MAX_IN_FLIGHT = 100;
const DEFAULT_REQUEST_TIMEOUT_MS = 2000;
const KNOWN_KEYS = ['ratePerSecond', 'defaultWeight', 'maxInFlight', 'requestTimeoutMs', 'weights'];

export type WeightedTenant = {
  readonly id: number;
  readonly weight: number;
};

export type TrafficConfig = {
  readonly ratePerSecond: number;
  readonly maxInFlight: number;
  readonly requestTimeoutMs: number;
  readonly weightedTenants: readonly WeightedTenant[];
};

type RawConfig = Record<string, unknown>;
type Fail = (problem: string) => Error;

export function loadTrafficConfig(path: string, tenantIds: readonly number[]): TrafficConfig {
  return parseTrafficConfig(readText(path), path, tenantIds);
}

export function parseTrafficConfig(text: string, path: string, tenantIds: readonly number[]): TrafficConfig {
  const fail: Fail = (problem) => configError(path, problem);
  const raw = parseObject(text, fail);
  const unknownKey = Object.keys(raw).find((key) => !KNOWN_KEYS.includes(key));
  if (unknownKey !== undefined) {
    throw fail(`unknown key "${unknownKey}"`);
  }
  const ratePerSecond = requiredRate(raw.ratePerSecond, fail);
  const defaultWeight = optionalWeight(raw.defaultWeight, DEFAULT_WEIGHT, 'defaultWeight', fail);
  const maxInFlight = optionalPositiveInteger(raw.maxInFlight, DEFAULT_MAX_IN_FLIGHT, 'maxInFlight', fail);
  const requestTimeoutMs = optionalPositiveInteger(
    raw.requestTimeoutMs,
    DEFAULT_REQUEST_TIMEOUT_MS,
    'requestTimeoutMs',
    fail,
  );
  const weights = tenantWeights(raw.weights, tenantIds, fail);
  const weightedTenants = tenantIds
    .map((id) => ({ id, weight: weights.get(id) ?? defaultWeight }))
    .filter((tenant) => tenant.weight > 0);
  if (weightedTenants.length === 0) {
    throw fail('every tenant has weight 0 (weights, defaultWeight)');
  }
  return { ratePerSecond, maxInFlight, requestTimeoutMs, weightedTenants };
}

function requiredRate(value: unknown, fail: Fail): number {
  if (value === undefined) {
    throw fail('"ratePerSecond" is required');
  }
  if (!isFiniteNumber(value) || value <= 0 || value > MAX_RATE_PER_SECOND) {
    throw fail(`"ratePerSecond" must be a number > 0 and <= ${MAX_RATE_PER_SECOND}, got ${JSON.stringify(value)}`);
  }
  return value;
}

function optionalWeight(value: unknown, fallback: number, field: string, fail: Fail): number {
  if (value === undefined) {
    return fallback;
  }
  if (!isFiniteNumber(value) || value < 0) {
    throw fail(`"${field}" must be a number >= 0, got ${JSON.stringify(value)}`);
  }
  return value;
}

function optionalPositiveInteger(value: unknown, fallback: number, field: string, fail: Fail): number {
  if (value === undefined) {
    return fallback;
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1) {
    throw fail(`"${field}" must be an integer >= 1, got ${JSON.stringify(value)}`);
  }
  return value;
}

function tenantWeights(value: unknown, tenantIds: readonly number[], fail: Fail): Map<number, number> {
  if (value === undefined) {
    return new Map();
  }
  if (!isPlainObject(value)) {
    throw fail('"weights" must be an object of tenant id to weight');
  }
  const weights = new Map<number, number>();
  for (const [key, weight] of Object.entries(value)) {
    const id = Number(key);
    if (String(id) !== key || !tenantIds.includes(id)) {
      throw fail(`"weights" names tenant id "${key}" that is not in the tenants file`);
    }
    weights.set(id, optionalWeight(weight, 0, `weights.${key}`, fail));
  }
  return weights;
}

function parseObject(text: string, fail: Fail): RawConfig {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw fail('not valid JSON');
  }
  if (!isPlainObject(parsed)) {
    throw fail('expected a JSON object');
  }
  return parsed;
}

function readText(path: string): string {
  try {
    return readFileSync(path, 'utf8');
  } catch (error) {
    throw configError(path, `cannot read file (${(error as NodeJS.ErrnoException).code ?? String(error)})`);
  }
}

function configError(path: string, problem: string): Error {
  return new Error(`traffic config ${path}: ${problem}`);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isPlainObject(value: unknown): value is RawConfig {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
