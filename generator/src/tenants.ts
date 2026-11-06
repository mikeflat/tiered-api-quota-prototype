import { readFileSync } from 'node:fs';

export function loadTenantIds(path: string): number[] {
  return parseTenantIds(readText(path), path);
}

export function parseTenantIds(text: string, path: string): number[] {
  const entries = parseJson(text, path);
  if (!Array.isArray(entries) || entries.length === 0) {
    throw tenantsError(path, 'expected a non-empty JSON array of tenants');
  }
  const ids = entries.map((entry: unknown, index) => tenantIdAt(entry, index, path));
  const duplicate = ids.find((id, index) => ids.indexOf(id) !== index);
  if (duplicate !== undefined) {
    throw tenantsError(path, `duplicate tenant id ${duplicate}`);
  }
  return ids;
}

function tenantIdAt(entry: unknown, index: number, path: string): number {
  const id = typeof entry === 'object' && entry !== null ? (entry as { id?: unknown }).id : undefined;
  if (typeof id !== 'number' || !Number.isSafeInteger(id) || id < 1) {
    throw tenantsError(path, `entry ${index} needs a positive integer "id", got ${JSON.stringify(id)}`);
  }
  return id;
}

function readText(path: string): string {
  try {
    return readFileSync(path, 'utf8');
  } catch (error) {
    throw tenantsError(path, `cannot read file (${(error as NodeJS.ErrnoException).code ?? String(error)})`);
  }
}

function parseJson(text: string, path: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    throw tenantsError(path, 'not valid JSON');
  }
}

function tenantsError(path: string, problem: string): Error {
  return new Error(`tenants file ${path}: ${problem}`);
}
