import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { readFilePaths } from './config.ts';
import { loadTenantIds, parseTenantIds } from './tenants.ts';

const PATH = '/config/tenants.json';

describe('parseTenantIds', () => {
  it('reads_ids_and_ignores_other_fields', () => {
    expect(parseTenantIds('[{"id":1,"name":"A","tier":"Bronze"},{"id":7,"extra":true}]', PATH)).toEqual([1, 7]);
  });

  it('invalid_json_names_path', () => {
    expect(() => parseTenantIds('[{', PATH)).toThrow(`tenants file ${PATH}: not valid JSON`);
  });

  it.each(['[]', '{}', '"x"'])('empty_array_is_rejected %s', (text) => {
    expect(() => parseTenantIds(text, PATH)).toThrow('non-empty JSON array');
  });

  it.each(['[{"id":0}]', '[{"id":-3}]', '[{"id":1.5}]', '[{"id":"4"}]', '[{"name":"no id"}]', '[null]'])(
    'non_positive_or_fractional_id_is_rejected %s',
    (text) => {
      expect(() => parseTenantIds(text, PATH)).toThrow('entry 0 needs a positive integer "id"');
    },
  );

  it('duplicate_id_is_rejected', () => {
    expect(() => parseTenantIds('[{"id":3},{"id":3}]', PATH)).toThrow('duplicate tenant id 3');
  });
});

describe('loadTenantIds', () => {
  it('seeded_file_has_fifteen_tenants', () => {
    expect(loadTenantIds(readFilePaths({}).tenantsFile)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]);
  });

  it('missing_file_names_path', () => {
    const missing = join(mkdtempSync(join(tmpdir(), 'tenants-')), 'absent.json');
    expect(() => loadTenantIds(missing)).toThrow(`tenants file ${missing}: cannot read file (ENOENT)`);
  });

  it('reads_a_file_from_disk', () => {
    const file = join(mkdtempSync(join(tmpdir(), 'tenants-')), 'tenants.json');
    writeFileSync(file, '[{"id":5}]');
    expect(loadTenantIds(file)).toEqual([5]);
  });
});
