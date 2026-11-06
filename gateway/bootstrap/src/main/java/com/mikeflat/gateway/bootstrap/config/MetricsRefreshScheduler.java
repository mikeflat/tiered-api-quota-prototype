package com.mikeflat.gateway.bootstrap.config;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MetricsRefreshScheduler implements AutoCloseable {

    public static final String THREAD_NAME = "metrics-refresh";
    public static final Duration EVERY_SECOND = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(MetricsRefreshScheduler.class);

    private final ScheduledExecutorService executor;

    private MetricsRefreshScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public static MetricsRefreshScheduler start(Runnable refresh, Duration interval) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(THREAD_NAME).daemon(true).factory());
        long periodMillis = interval.toMillis();
        executor.scheduleAtFixedRate(() -> runSurvivingFailures(refresh), 0, periodMillis, TimeUnit.MILLISECONDS);
        return new MetricsRefreshScheduler(executor);
    }

    private static void runSurvivingFailures(Runnable refresh) {
        try {
            refresh.run();
        } catch (RuntimeException unexpected) {
            log.warn("Traffic metrics refresh failed unexpectedly, retrying on the next tick", unexpected);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
