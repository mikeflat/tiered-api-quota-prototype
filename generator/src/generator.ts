import { createPacer } from './pacing.ts';
import { resourceEndpoint, sendRequest, type FetchFunction } from './send-request.ts';
import { createSummary } from './summary.ts';
import type { TrafficConfig } from './traffic-config.ts';
import { createWeightedPicker, type RandomSource } from './weighted-pick.ts';

const TICK_MS = 20;
const SUMMARY_INTERVAL_MS = 10_000;

export type GeneratorDependencies = {
  readonly fetchFunction: FetchFunction;
  readonly log: (message: string) => void;
  readonly random: RandomSource;
  readonly nowMs: () => number;
};

export type RunningGenerator = {
  stop(): Promise<void>;
};

export function startGenerator(
  gatewayUrl: URL,
  traffic: TrafficConfig,
  dependencies: GeneratorDependencies,
): RunningGenerator {
  const { fetchFunction, log, random, nowMs } = dependencies;
  const endpoint = resourceEndpoint(gatewayUrl);
  const pickTenant = createWeightedPicker(traffic.weightedTenants, random);
  const pacer = createPacer(traffic.ratePerSecond, nowMs());
  const summary = createSummary();
  const stopController = new AbortController();
  const inFlight = new Set<Promise<void>>();

  const startRequest = (): void => {
    summary.recordSent();
    const request = sendRequest(fetchFunction, endpoint, pickTenant(), traffic.requestTimeoutMs, stopController.signal)
      .then((outcome) => summary.recordOutcome(outcome))
      .finally(() => inFlight.delete(request));
    inFlight.add(request);
  };

  const startDueRequests = (): void => {
    const due = pacer.takeDue(nowMs());
    for (let i = 0; i < due; i++) {
      if (inFlight.size >= traffic.maxInFlight) {
        summary.recordSkipped();
      } else {
        startRequest();
      }
    }
  };

  const tickTimer = setInterval(startDueRequests, TICK_MS);
  const summaryTimer = setInterval(() => log(summary.takeLine()), SUMMARY_INTERVAL_MS);

  return {
    async stop() {
      clearInterval(tickTimer);
      clearInterval(summaryTimer);
      stopController.abort();
      await Promise.allSettled([...inFlight]);
      log(summary.takeLine());
    },
  };
}
