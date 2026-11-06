import { describe, expect, it } from 'vitest';
import { createSummary } from './summary.ts';

describe('createSummary', () => {
  it('line_contains_all_counts', () => {
    const summary = createSummary();
    for (let i = 0; i < 4; i++) summary.recordSent();
    summary.recordOutcome({ kind: 'ok' });
    summary.recordOutcome({ kind: 'ok' });
    summary.recordOutcome({ kind: 'rejected' });
    summary.recordSkipped();
    expect(summary.takeLine()).toBe('summary: sent=4 200=2 429=1 errors=0 skipped=1');
  });

  it('counts_reset_after_take', () => {
    const summary = createSummary();
    summary.recordSent();
    summary.recordOutcome({ kind: 'error', reason: 'timeout' });
    summary.takeLine();
    expect(summary.takeLine()).toBe('summary: sent=0 200=0 429=0 errors=0 skipped=0');
  });

  it('last_error_shown_only_with_errors', () => {
    const summary = createSummary();
    summary.recordOutcome({ kind: 'error', reason: 'ECONNREFUSED' });
    summary.recordOutcome({ kind: 'error', reason: 'status 503' });
    expect(summary.takeLine()).toBe('summary: sent=0 200=0 429=0 errors=2 skipped=0 last-error=status 503');
    summary.recordOutcome({ kind: 'ok' });
    expect(summary.takeLine()).not.toContain('last-error');
  });

  it('stopped_outcomes_are_not_counted', () => {
    const summary = createSummary();
    summary.recordOutcome({ kind: 'stopped' });
    expect(summary.takeLine()).toBe('summary: sent=0 200=0 429=0 errors=0 skipped=0');
  });

  it('skipped_is_not_counted_as_sent', () => {
    const summary = createSummary();
    summary.recordSkipped();
    summary.recordSkipped();
    expect(summary.takeLine()).toBe('summary: sent=0 200=0 429=0 errors=0 skipped=2');
  });
});
