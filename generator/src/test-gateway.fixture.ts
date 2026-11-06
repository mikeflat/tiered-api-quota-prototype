import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';

export type TestGateway = {
  readonly url: URL;
  readonly tenantIds: string[];
  readonly paths: string[];
  maxOpenRequests: number;
  close(): Promise<void>;
};

export type GatewayBehaviour = 'answer' | 'hang';

export async function startTestGateway(
  behaviour: GatewayBehaviour,
  statusFor: (tenantId: string) => number = () => 200,
  port = 0,
): Promise<TestGateway> {
  let openRequests = 0;
  const gateway: TestGateway = {
    url: new URL('http://127.0.0.1'),
    tenantIds: [],
    paths: [],
    maxOpenRequests: 0,
    close: async () => {},
  };
  const server = createServer((request: IncomingMessage, response: ServerResponse) => {
    const tenantId = String(request.headers['x-tenant-id'] ?? '');
    gateway.tenantIds.push(tenantId);
    gateway.paths.push(`${request.method} ${request.url}`);
    openRequests++;
    gateway.maxOpenRequests = Math.max(gateway.maxOpenRequests, openRequests);
    request.on('close', () => openRequests--);
    if (behaviour === 'answer') {
      response.writeHead(statusFor(tenantId), { 'content-type': 'application/json' });
      response.end('{"ok":true}');
    }
  });
  await new Promise<void>((resolve) => server.listen(port, '127.0.0.1', resolve));
  gateway.url.port = String((server.address() as AddressInfo).port);
  gateway.close = () =>
    new Promise<void>((resolve) => {
      server.closeAllConnections();
      server.close(() => resolve());
    });
  return gateway;
}

export async function unusedPortUrl(): Promise<URL> {
  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = (server.address() as AddressInfo).port;
  await new Promise<void>((resolve) => server.close(() => resolve()));
  return new URL(`http://127.0.0.1:${port}`);
}
