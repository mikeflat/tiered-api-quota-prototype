import { describeGatewayUrl, readFilePaths, readGatewayUrl } from './config.ts';
import { startGenerator } from './generator.ts';
import { loadTenantIds } from './tenants.ts';
import { loadTrafficConfig, type TrafficConfig } from './traffic-config.ts';

type StartupSettings = {
  readonly gatewayUrl: URL;
  readonly tenantCount: number;
  readonly traffic: TrafficConfig;
};

function log(message: string): void {
  console.log(`${new Date().toISOString()} ${message}`);
}

function loadStartupSettings(): StartupSettings {
  const gatewayUrl = readGatewayUrl(process.env);
  const paths = readFilePaths(process.env);
  const tenantIds = loadTenantIds(paths.tenantsFile);
  const traffic = loadTrafficConfig(paths.trafficConfigFile, tenantIds);
  return { gatewayUrl, tenantCount: tenantIds.length, traffic };
}

function main(): void {
  let settings: StartupSettings;
  try {
    settings = loadStartupSettings();
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
    return;
  }
  const { gatewayUrl, tenantCount, traffic } = settings;

  log(
    `generator started: gateway=${describeGatewayUrl(gatewayUrl)}, tenants=${tenantCount}, ` +
      `rate=${traffic.ratePerSecond} req/s, max-in-flight=${traffic.maxInFlight}`,
  );

  const generator = startGenerator(gatewayUrl, traffic, {
    fetchFunction: fetch,
    log,
    random: Math.random,
    nowMs: () => performance.now(),
  });

  const stop = (signal: NodeJS.Signals): void => {
    process.off('SIGTERM', stop);
    process.off('SIGINT', stop);
    void generator.stop().then(() => {
      log(`generator stopped (${signal})`);
      process.exitCode = 0;
    });
  };
  process.once('SIGTERM', stop);
  process.once('SIGINT', stop);
}

main();
