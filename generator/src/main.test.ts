import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';

const MAIN = fileURLToPath(new URL('./main.ts', import.meta.url));
const TIMEOUT_MS = 5_000;

let child: ChildProcess | undefined;

afterEach(() => {
  child?.kill();
  child = undefined;
});

function start(gatewayUrl?: string): ChildProcess {
  const env: NodeJS.ProcessEnv = { ...process.env };
  delete env.GATEWAY_URL;
  if (gatewayUrl !== undefined) env.GATEWAY_URL = gatewayUrl;
  child = spawn(process.execPath, [MAIN], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  return child;
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

function waitForExit(proc: ChildProcess): Promise<{ code: number | null; stderr: string }> {
  return new Promise((resolve) => {
    let stderr = '';
    proc.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString();
    });
    proc.once('close', (code) => resolve({ code, stderr }));
  });
}

describe('main', () => {
  it('logs a startup line with the default gateway and no traffic', { timeout: TIMEOUT_MS }, async () => {
    const out = await waitForStdout(start(), 'generator started');
    expect(out).toContain('gateway=http://localhost:8080,');
    expect(out).toContain('traffic=off');
  });

  it('exits non-zero with a message on an invalid GATEWAY_URL', { timeout: TIMEOUT_MS }, async () => {
    const { code, stderr } = await waitForExit(start('ftp://gateway'));
    expect(code).not.toBe(0);
    expect(stderr).toContain('GATEWAY_URL');
    expect(stderr).toContain('ftp://gateway');
  });
});
