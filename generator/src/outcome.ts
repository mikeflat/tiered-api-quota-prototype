export type Outcome =
  | { readonly kind: 'ok' }
  | { readonly kind: 'rejected' }
  | { readonly kind: 'error'; readonly reason: string }
  | { readonly kind: 'stopped' };

const HTTP_OK = 200;
const HTTP_TOO_MANY_REQUESTS = 429;

export function classifyStatus(status: number): Outcome {
  if (status === HTTP_OK) {
    return { kind: 'ok' };
  }
  if (status === HTTP_TOO_MANY_REQUESTS) {
    return { kind: 'rejected' };
  }
  return { kind: 'error', reason: `status ${status}` };
}

export function classifyError(error: unknown, stopRequested: boolean): Outcome {
  if (stopRequested) {
    return { kind: 'stopped' };
  }
  if (error instanceof Error && error.name === 'TimeoutError') {
    return { kind: 'error', reason: 'timeout' };
  }
  return { kind: 'error', reason: networkCode(error) ?? errorName(error) };
}

function networkCode(error: unknown): string | undefined {
  const cause = error instanceof Error ? error.cause : undefined;
  const code = typeof cause === 'object' && cause !== null ? (cause as { code?: unknown }).code : undefined;
  return typeof code === 'string' ? code : undefined;
}

function errorName(error: unknown): string {
  return error instanceof Error ? error.name : 'unknown error';
}
