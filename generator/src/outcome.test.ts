import { describe, expect, it } from 'vitest';
import { classifyError, classifyStatus } from './outcome.ts';

describe('classifyStatus', () => {
  it('status_200_is_ok', () => {
    expect(classifyStatus(200)).toEqual({ kind: 'ok' });
  });

  it('status_429_is_rejected', () => {
    expect(classifyStatus(429)).toEqual({ kind: 'rejected' });
  });

  it.each([201, 400, 404, 503])('other_status_is_error_with_status_reason (%s)', (status) => {
    expect(classifyStatus(status)).toEqual({ kind: 'error', reason: `status ${status}` });
  });
});

describe('classifyError', () => {
  it('timeout_error_reason_is_timeout', () => {
    expect(classifyError(new DOMException('timed out', 'TimeoutError'), false)).toEqual({
      kind: 'error',
      reason: 'timeout',
    });
  });

  it('connection_refused_reason_is_code', () => {
    const error = new TypeError('fetch failed', { cause: Object.assign(new Error('connect'), { code: 'ECONNREFUSED' }) });
    expect(classifyError(error, false)).toEqual({ kind: 'error', reason: 'ECONNREFUSED' });
  });

  it('stop_abort_reason_is_stopped', () => {
    expect(classifyError(new DOMException('aborted', 'AbortError'), true)).toEqual({ kind: 'stopped' });
  });

  it('unknown_error_uses_its_name', () => {
    expect(classifyError(new TypeError('fetch failed'), false)).toEqual({ kind: 'error', reason: 'TypeError' });
    expect(classifyError('boom', false)).toEqual({ kind: 'error', reason: 'unknown error' });
  });
});
