import { describeGatewayUrl, readGatewayUrl } from './config.ts';

const KEEP_ALIVE_MS = 60 * 60 * 1000;

function log(message: string): void {
  console.log(`${new Date().toISOString()} ${message}`);
}

function main(): void {
  let gatewayUrl: URL;
  try {
    gatewayUrl = readGatewayUrl(process.env);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
    return;
  }

  log(`generator started: gateway=${describeGatewayUrl(gatewayUrl)}, traffic=off (skeleton)`);

  // Nothing else holds the event loop open yet; the traffic loop will replace this.
  const keepAlive = setInterval(() => {}, KEEP_ALIVE_MS);

  const stop = (signal: NodeJS.Signals): void => {
    clearInterval(keepAlive);
    process.off('SIGTERM', stop);
    process.off('SIGINT', stop);
    log(`generator stopped (${signal})`);
    process.exitCode = 0;
  };
  process.once('SIGTERM', stop);
  process.once('SIGINT', stop);
}

main();
