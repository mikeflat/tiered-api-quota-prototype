import { describe, expect, it } from 'vitest';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describeGatewayUrl, readFilePaths, readGatewayUrl } from './config.ts';

describe('readGatewayUrl', () => {
  it('defaults_to_localhost_8080_when_unset', () => {
    expect(readGatewayUrl({}).href).toBe('http://localhost:8080/');
  });

  it('defaults_to_localhost_8080_when_blank', () => {
    expect(readGatewayUrl({ GATEWAY_URL: '   ' }).href).toBe('http://localhost:8080/');
  });

  it('uses_an_http_override', () => {
    const url = readGatewayUrl({ GATEWAY_URL: 'http://gateway:9090' });
    expect(url.protocol).toBe('http:');
    expect(url.host).toBe('gateway:9090');
  });

  it('accepts_an_https_address', () => {
    const url = readGatewayUrl({ GATEWAY_URL: 'https://quota.example.com' });
    expect(url.protocol).toBe('https:');
    expect(url.host).toBe('quota.example.com');
  });

  it.each(['not a url', 'ftp://gateway'])('rejects_%j_naming_variable_and_value', (value) => {
    expect(() => readGatewayUrl({ GATEWAY_URL: value })).toThrow(
      `GATEWAY_URL must be an absolute http(s) URL, got "${value}"`,
    );
  });
});

describe('describeGatewayUrl', () => {
  it('drops_trailing_slash_for_empty_path', () => {
    expect(describeGatewayUrl(new URL('http://gateway:9090'))).toBe('http://gateway:9090');
  });

  it('keeps_non_empty_path_as_given', () => {
    expect(describeGatewayUrl(new URL('http://gateway:9090/base/'))).toBe('http://gateway:9090/base/');
  });
});

describe('readFilePaths', () => {
  const generatorDir = fileURLToPath(new URL('..', import.meta.url));

  it('tenants_path_defaults_to_repository_config', () => {
    expect(readFilePaths({}).tenantsFile).toBe(resolve(generatorDir, '..', 'config', 'tenants.json'));
  });

  it('traffic_path_defaults_to_generator_traffic_json', () => {
    expect(readFilePaths({}).trafficConfigFile).toBe(resolve(generatorDir, 'traffic.json'));
  });

  it('blank_paths_use_defaults', () => {
    expect(readFilePaths({ TENANTS_FILE: ' ', TRAFFIC_CONFIG_FILE: '' })).toEqual(readFilePaths({}));
  });

  it('paths_can_be_overridden', () => {
    expect(readFilePaths({ TENANTS_FILE: '/config/tenants.json', TRAFFIC_CONFIG_FILE: '/tmp/shape.json' })).toEqual({
      tenantsFile: '/config/tenants.json',
      trafficConfigFile: '/tmp/shape.json',
    });
  });
});
