import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { startTestGateway, unusedPortUrl, type TestGateway } from './test-gateway.fixture.ts';

const MAIN = fileURLToPath(new URL('./main.ts', import.meta.url));
const TIMEOUT_MS = 10_000;

let child: ChildProcess | undefined;
let gateway: TestGateway | undefined;

afterEach(async () => {
  child?.kill();
  child = undefined;
  await gateway?.close();
  gateway = undefined;
});

function start(env: Record<string, string>): ChildProcess {
  const childEnv: NodeJS.ProcessEnv = { ...process.env };
  delete childEnv.GATEWAY_URL;
  delete childEnv.TENANTS_FILE;
  delete childEnv.TRAFFIC_CONFIG_FILE;
  child = spawn(process.execPath, [MAIN], { env: { ...childEnv, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  return child;
}

function tempFile(name: string, content: string): string {
  const file = join(mkdtempSync(join(tmpdir(), 'generator-')), name);
  writeFileSync(file, content);
  return file;
}

function waitForStdout(proc: ChildProcess, text: string): Promise<string> {
  return new Promise((resolve, reject) => {
    let out = '';
    proc.stdout?.on('data', (chunk: Buffer) => {
      out += chunk.toString();
      if (out.includes(text)) resolve(out);
    });
    proc.once('exit', (code) => reject(new Error(`exited with ${code} before printing "${text}": ${out}`)));
  });
}

function waitForExit(proc: ChildProcess): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    let stdout = '';
    let stderr = '';
    proc.stdout?.on('data', (chunk: Buffer) => {
      stdout += chunk.toString();
    });
    proc.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString();
    });
    proc.once('close', (code) => resolve({ code, stdout, stderr }));
  });
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe('main', () => {
  it('startup_line_names_gateway_tenants_and_rate', { timeout: TIMEOUT_MS }, async () => {
    const out = await waitForStdout(start({}), 'generator started');
    expect(out).toContain('gateway=http://localhost:8080, tenants=15, rate=200 req/s, max-in-flight=100');
  });

  it('invalid_gateway_url_exits_non_zero_naming_variable_and_value', { timeout: TIMEOUT_MS }, async () => {
    const { code, stderr } = await waitForExit(start({ GATEWAY_URL: 'ftp://gateway' }));
    expect(code).not.toBe(0);
    expect(stderr).toContain('GATEWAY_URL');
    expect(stderr).toContain('ftp://gateway');
  });

  it('invalid_tenants_file_exits_non_zero_naming_path', { timeout: TIMEOUT_MS }, async () => {
    const tenantsFile = tempFile('tenants.json', '[]');
    const { code, stderr } = await waitForExit(start({ TENANTS_FILE: tenantsFile }));
    expect(code).toBe(1);
    expect(stderr).toContain(tenantsFile);
  });

  it('invalid_traffic_config_exits_non_zero_naming_field', { timeout: TIMEOUT_MS }, async () => {
    const trafficFile = tempFile('traffic.json', '{"ratePerSecond": 0}');
    const { code, stderr } = await waitForExit(start({ TRAFFIC_CONFIG_FILE: trafficFile }));
    expect(code).toBe(1);
    expect(stderr).toContain(trafficFile);
    expect(stderr).toContain('ratePerSecond');
  });

  it('requests_carry_tenant_ids_from_tenants_file', { timeout: TIMEOUT_MS }, async () => {
    gateway = await startTestGateway('answer');
    const proc = start({
      GATEWAY_URL: gateway.url.href,
      TENANTS_FILE: tempFile('tenants.json', '[{"id":21},{"id":22}]'),
      TRAFFIC_CONFIG_FILE: tempFile('traffic.json', '{"ratePerSecond": 100}'),
    });
    expect(await waitForStdout(proc, 'generator started')).toContain('tenants=2, rate=100 req/s');
    await delay(500);
    expect(gateway.paths.length).toBeGreaterThan(0);
    expect(new Set(gateway.tenantIds)).toEqual(new Set(['21', '22']));
  });

  it('keeps_running_without_a_gateway', { timeout: TIMEOUT_MS }, async () => {
    const proc = start({ GATEWAY_URL: (await unusedPortUrl()).href });
    await waitForStdout(proc, 'generator started');
    await delay(1000);
    expect(proc.exitCode).toBeNull();
  });

  it.skipIf(process.platform === 'win32')(
    'sigint_logs_final_summary_and_stop_line_and_exits_zero',
    { timeout: TIMEOUT_MS },
    async () => {
      gateway = await startTestGateway('hang');
      const proc = start({ GATEWAY_URL: gateway.url.href });
      await waitForStdout(proc, 'generator started');
      await delay(300);
      const exit = waitForExit(proc);
      const signalledAt = performance.now();
      proc.kill('SIGINT');
      const { code, stdout } = await exit;
      expect(performance.now() - signalledAt).toBeLessThan(3000);
      expect(code).toBe(0);
      const lines = stdout.trim().split('\n');
      expect(lines.at(-2)).toMatch(/summary: sent=\d+ 200=0 429=0 errors=0 skipped=\d+$/);
      expect(lines.at(-1)).toMatch(/generator stopped \(SIGINT\)$/);
    },
  );
});
