import { describe, expect, it } from 'vitest';
import { describeGatewayUrl, readGatewayUrl } from './config.ts';

describe('readGatewayUrl', () => {
  it('defaults to http://localhost:8080 when GATEWAY_URL is unset', () => {
    expect(readGatewayUrl({}).href).toBe('http://localhost:8080/');
  });

  it('defaults to http://localhost:8080 when GATEWAY_URL is blank', () => {
    expect(readGatewayUrl({ GATEWAY_URL: '   ' }).href).toBe('http://localhost:8080/');
  });

  it('uses an http override', () => {
    const url = readGatewayUrl({ GATEWAY_URL: 'http://gateway:9090' });
    expect(url.protocol).toBe('http:');
    expect(url.host).toBe('gateway:9090');
  });

  it('accepts an https address', () => {
    const url = readGatewayUrl({ GATEWAY_URL: 'https://quota.example.com' });
    expect(url.protocol).toBe('https:');
    expect(url.host).toBe('quota.example.com');
  });

  it.each(['not a url', 'ftp://gateway'])('rejects %j with a message naming the variable and value', (value) => {
    expect(() => readGatewayUrl({ GATEWAY_URL: value })).toThrow(
      `GATEWAY_URL must be an absolute http(s) URL, got "${value}"`,
    );
  });
});

describe('describeGatewayUrl', () => {
  it('drops the trailing slash when the path is empty', () => {
    expect(describeGatewayUrl(new URL('http://gateway:9090'))).toBe('http://gateway:9090');
  });

  it('keeps a non-empty path as given', () => {
    expect(describeGatewayUrl(new URL('http://gateway:9090/base/'))).toBe('http://gateway:9090/base/');
  });
});
