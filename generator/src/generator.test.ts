import { afterEach, describe, expect, it } from 'vitest';
import { startGenerator, type RunningGenerator } from './generator.ts';
import { startTestGateway, unusedPortUrl, type TestGateway } from './test-gateway.fixture.ts';
import type { TrafficConfig } from './traffic-config.ts';

const TIMEOUT_MS = 10_000;

let gateway: TestGateway | undefined;
let generator: RunningGenerator | undefined;

afterEach(async () => {
  await generator?.stop();
  await gateway?.close();
  generator = undefined;
  gateway = undefined;
});

function traffic(overrides: Partial<TrafficConfig> = {}): TrafficConfig {
  return {
    ratePerSecond: 200,
    maxInFlight: 100,
    requestTimeoutMs: 2000,
    weightedTenants: [
      { id: 3, weight: 1 },
      { id: 4, weight: 1 },
    ],
    ...overrides,
  };
}

function start(url: URL, config: TrafficConfig, lines: string[]): RunningGenerator {
  generator = startGenerator(url, config, {
    fetchFunction: fetch,
    log: (line) => lines.push(line),
    random: Math.random,
    nowMs: () => performance.now(),
  });
  return generator;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function countIn(line: string, field: string): number {
  return Number(new RegExp(`${field}=(\\d+)`).exec(line)?.[1] ?? Number.NaN);
}

describe('startGenerator', () => {
  it('requests_carry_tenant_ids_of_weighted_tenants', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('answer');
    start(gateway.url, traffic(), []);
    await delay(500);
    expect(gateway.paths.length).toBeGreaterThan(0);
    expect(new Set(gateway.paths)).toEqual(new Set(['GET /api/v1/resource']));
    expect(new Set(gateway.tenantIds)).toEqual(new Set(['3', '4']));
  });

  it('only_weighted_tenants_receive_traffic', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('answer');
    start(gateway.url, traffic({ weightedTenants: [{ id: 9, weight: 1 }] }), []);
    await delay(300);
    expect(new Set(gateway.tenantIds)).toEqual(new Set(['9']));
  });

  it('paces_requests_to_the_configured_rate', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('answer');
    start(gateway.url, traffic({ ratePerSecond: 100 }), []);
    await delay(1000);
    expect(gateway.paths.length).toBeGreaterThanOrEqual(85);
    expect(gateway.paths.length).toBeLessThanOrEqual(110);
  });

  it('stop_logs_final_summary_with_outcomes', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('answer', (tenantId) => (tenantId === '4' ? 429 : 200));
    const lines: string[] = [];
    const running = start(gateway.url, traffic(), lines);
    await delay(400);
    await running.stop();
    generator = undefined;
    const line = lines.at(-1) ?? '';
    expect(line).toMatch(/^summary: sent=\d+ 200=\d+ 429=\d+ errors=0 skipped=0$/);
    expect(countIn(line, '200')).toBeGreaterThan(0);
    expect(countIn(line, '429')).toBeGreaterThan(0);
  });

  it('keeps_sending_and_counts_errors_without_a_gateway', { timeout: TIMEOUT_MS }, async () => {
    const lines: string[] = [];
    const running = start(await unusedPortUrl(), traffic({ ratePerSecond: 50 }), lines);
    await delay(500);
    await running.stop();
    generator = undefined;
    const line = lines.at(-1) ?? '';
    expect(countIn(line, 'errors')).toBeGreaterThan(5);
    expect(countIn(line, 'errors')).toBe(countIn(line, 'sent'));
    expect(line).toContain('last-error=ECONNREFUSED');
  });

  it('resumes_without_restart_when_the_gateway_comes_back', { timeout: TIMEOUT_MS }, async () => {
    const url = await unusedPortUrl();
    const lines: string[] = [];
    const running = start(url, traffic({ ratePerSecond: 50 }), lines);
    await delay(300);
    gateway = await startTestGateway('answer', () => 200, Number(url.port));
    await delay(300);
    await running.stop();
    generator = undefined;
    const line = lines.at(-1) ?? '';
    expect(countIn(line, 'errors')).toBeGreaterThan(0);
    expect(countIn(line, '200')).toBeGreaterThan(0);
    expect(gateway.paths.length).toBe(countIn(line, '200'));
  });

  it('in_flight_never_exceeds_max_against_hanging_gateway', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('hang');
    const lines: string[] = [];
    const running = start(gateway.url, traffic({ maxInFlight: 5, requestTimeoutMs: 60_000 }), lines);
    await delay(500);
    expect(gateway.maxOpenRequests).toBe(5);
    await running.stop();
    generator = undefined;
    const line = lines.at(-1) ?? '';
    expect(countIn(line, 'sent')).toBe(5);
    expect(countIn(line, 'skipped')).toBeGreaterThan(50);
    expect(countIn(line, 'errors')).toBe(0);
  });

  it('slow_gateway_requests_time_out_as_errors', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('hang');
    const lines: string[] = [];
    const running = start(gateway.url, traffic({ ratePerSecond: 20, requestTimeoutMs: 100 }), lines);
    await delay(600);
    await running.stop();
    generator = undefined;
    const line = lines.at(-1) ?? '';
    expect(countIn(line, 'errors')).toBeGreaterThan(0);
    expect(line).toContain('last-error=timeout');
  });

  it('stop_returns_quickly_while_gateway_hangs', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('hang');
    const running = start(gateway.url, traffic({ requestTimeoutMs: 60_000 }), []);
    await delay(200);
    const stoppedAt = performance.now();
    await running.stop();
    generator = undefined;
    expect(performance.now() - stoppedAt).toBeLessThan(1000);
  });
});
