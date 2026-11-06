import { classifyError, classifyStatus, type Outcome } from './outcome.ts';

export type FetchFunction = (input: URL, init: RequestInit) => Promise<Response>;

export function resourceEndpoint(gatewayUrl: URL): URL {
  const base = new URL(gatewayUrl);
  if (!base.pathname.endsWith('/')) {
    base.pathname = `${base.pathname}/`;
  }
  base.search = '';
  base.hash = '';
  return new URL('api/v1/resource', base);
}

export async function sendRequest(
  fetchFunction: FetchFunction,
  endpoint: URL,
  tenantId: number,
  timeoutMs: number,
  stopSignal: AbortSignal,
): Promise<Outcome> {
  try {
    const response = await fetchFunction(endpoint, {
      method: 'GET',
      headers: { 'X-Tenant-Id': String(tenantId) },
      signal: AbortSignal.any([AbortSignal.timeout(timeoutMs), stopSignal]),
    });
    await response.arrayBuffer();
    return classifyStatus(response.status);
  } catch (error) {
    return classifyError(error, stopSignal.aborted);
  }
}
