import type { Outcome } from './outcome.ts';

export type Summary = {
  recordSent(): void;
  recordOutcome(outcome: Outcome): void;
  recordSkipped(): void;
  takeLine(): string;
};

export function createSummary(): Summary {
  let sent = 0;
  let ok = 0;
  let rejected = 0;
  let errors = 0;
  let skipped = 0;
  let lastErrorReason = '';

  return {
    recordSent() {
      sent++;
    },
    recordOutcome(outcome) {
      switch (outcome.kind) {
        case 'ok':
          ok++;
          break;
        case 'rejected':
          rejected++;
          break;
        case 'error':
          errors++;
          lastErrorReason = outcome.reason;
          break;
        case 'stopped':
          break;
      }
    },
    recordSkipped() {
      skipped++;
    },
    takeLine() {
      const counts = `summary: sent=${sent} 200=${ok} 429=${rejected} errors=${errors} skipped=${skipped}`;
      const line = errors > 0 ? `${counts} last-error=${lastErrorReason}` : counts;
      sent = ok = rejected = errors = skipped = 0;
      lastErrorReason = '';
      return line;
    },
  };
}
