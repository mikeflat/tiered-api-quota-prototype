import { describe, expect, it } from 'vitest';
import { resourceEndpoint, sendRequest, type FetchFunction } from './send-request.ts';

const ENDPOINT = new URL('http://gateway:8080/api/v1/resource');

type Call = { readonly url: URL; readonly init: RequestInit };

function recordingFetch(response: () => Response): { fetchFunction: FetchFunction; calls: Call[] } {
  const calls: Call[] = [];
  const fetchFunction: FetchFunction = async (url, init) => {
    calls.push({ url, init });
    return response();
  };
  return { fetchFunction, calls };
}

function hangingFetch(): FetchFunction {
  return (_url, init) =>
    new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(init.signal?.reason));
    });
}

describe('resourceEndpoint', () => {
  it('appends_resource_path_to_root', () => {
    expect(resourceEndpoint(new URL('http://gateway:8080')).href).toBe('http://gateway:8080/api/v1/resource');
  });

  it('endpoint_keeps_gateway_base_path', () => {
    expect(resourceEndpoint(new URL('http://host/base')).href).toBe('http://host/base/api/v1/resource');
    expect(resourceEndpoint(new URL('http://host/base/?q=1')).href).toBe('http://host/base/api/v1/resource');
  });
});

describe('sendRequest', () => {
  it('sends_get_to_resource_with_tenant_header', async () => {
    const { fetchFunction, calls } = recordingFetch(() => new Response('{}', { status: 200 }));
    await sendRequest(fetchFunction, ENDPOINT, 7, 1000, new AbortController().signal);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toBe(ENDPOINT);
    expect(calls[0]!.init.method).toBe('GET');
    expect(calls[0]!.init.headers).toEqual({ 'X-Tenant-Id': '7' });
  });

  it('drains_response_body', async () => {
    const response = new Response('payload', { status: 200 });
    const { fetchFunction } = recordingFetch(() => response);
    await sendRequest(fetchFunction, ENDPOINT, 1, 1000, new AbortController().signal);
    expect(response.bodyUsed).toBe(true);
  });

  it.each([
    [200, { kind: 'ok' }],
    [429, { kind: 'rejected' }],
    [503, { kind: 'error', reason: 'status 503' }],
  ])('maps_status_to_outcome (%s)', async (status, outcome) => {
    const { fetchFunction } = recordingFetch(() => new Response(null, { status }));
    expect(await sendRequest(fetchFunction, ENDPOINT, 1, 1000, new AbortController().signal)).toEqual(outcome);
  });

  it('timeout_becomes_timeout_error', async () => {
    const outcome = await sendRequest(hangingFetch(), ENDPOINT, 1, 20, new AbortController().signal);
    expect(outcome).toEqual({ kind: 'error', reason: 'timeout' });
  });

  it('stop_signal_becomes_stopped', async () => {
    const stop = new AbortController();
    const pending = sendRequest(hangingFetch(), ENDPOINT, 1, 60_000, stop.signal);
    stop.abort();
    expect(await pending).toEqual({ kind: 'stopped' });
  });

  it('thrown_error_never_escapes', async () => {
    const failing: FetchFunction = async () => {
      throw new TypeError('fetch failed', { cause: { code: 'ENOTFOUND' } });
    };
    expect(await sendRequest(failing, ENDPOINT, 1, 1000, new AbortController().signal)).toEqual({
      kind: 'error',
      reason: 'ENOTFOUND',
    });
  });

  it('body_read_failure_is_an_error', async () => {
    const brokenBody = new ReadableStream({
      start(controller) {
        controller.error(new TypeError('terminated'));
      },
    });
    const { fetchFunction } = recordingFetch(() => new Response(brokenBody, { status: 200 }));
    expect(await sendRequest(fetchFunction, ENDPOINT, 1, 1000, new AbortController().signal)).toEqual({
      kind: 'error',
      reason: 'TypeError',
    });
  });
});
