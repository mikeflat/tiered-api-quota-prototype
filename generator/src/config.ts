const DEFAULT_GATEWAY_URL = 'http://localhost:8080';

type Env = Readonly<Record<string, string | undefined>>;

/** Reads the gateway base address from GATEWAY_URL; throws if it is not an absolute http(s) URL. */
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

/** Formats the address for logs, without the trailing "/" of an empty path. */
export function describeGatewayUrl(url: URL): string {
  return url.pathname === '/' && url.search === '' && url.hash === '' ? url.href.slice(0, -1) : url.href;
}

function invalid(value: string): Error {
  return new Error(`GATEWAY_URL must be an absolute http(s) URL, got "${value}"`);
}
