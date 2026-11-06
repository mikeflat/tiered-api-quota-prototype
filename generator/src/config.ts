import { fileURLToPath } from 'node:url';

const DEFAULT_GATEWAY_URL = 'http://localhost:8080';

export type Env = Readonly<Record<string, string | undefined>>;

export function readGatewayUrl(env: Env): URL {
  const raw = env.GATEWAY_URL;
  const value = raw === undefined || raw.trim() === '' ? DEFAULT_GATEWAY_URL : raw.trim();
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw invalid(raw ?? '');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw invalid(raw ?? '');
  }
  return url;
}

export function describeGatewayUrl(url: URL): string {
  return url.pathname === '/' && url.search === '' && url.hash === '' ? url.href.slice(0, -1) : url.href;
}

function invalid(value: string): Error {
  return new Error(`GATEWAY_URL must be an absolute http(s) URL, got "${value}"`);
}

const DEFAULT_TENANTS_FILE = fileURLToPath(new URL('../../config/tenants.json', import.meta.url));
const DEFAULT_TRAFFIC_CONFIG_FILE = fileURLToPath(new URL('../traffic.json', import.meta.url));

export type FilePaths = {
  readonly tenantsFile: string;
  readonly trafficConfigFile: string;
};

export function readFilePaths(env: Env): FilePaths {
  return {
    tenantsFile: valueOrDefault(env.TENANTS_FILE, DEFAULT_TENANTS_FILE),
    trafficConfigFile: valueOrDefault(env.TRAFFIC_CONFIG_FILE, DEFAULT_TRAFFIC_CONFIG_FILE),
  };
}

function valueOrDefault(raw: string | undefined, fallback: string): string {
  return raw === undefined || raw.trim() === '' ? fallback : raw.trim();
}
