package com.mikeflat.gateway.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MetricsRefreshSchedulerTest {

    private static final Duration FAST = Duration.ofMillis(20);

    @Test
    void keeps_running_after_a_failed_refresh() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ranAfterFailure = new CountDownLatch(3);

        try (MetricsRefreshScheduler scheduler = MetricsRefreshScheduler.start(() -> {
            int run = runs.incrementAndGet();
            if (run > 1) {
                ranAfterFailure.countDown();
            }
            if (run <= 2) {
                throw new IllegalStateException("unexpected failure " + run);
            }
        }, FAST)) {
            assertThat(ranAfterFailure.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void thread_is_named_metrics_refresh_and_daemon() throws InterruptedException {
        AtomicReference<Thread> refreshThread = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);

        try (MetricsRefreshScheduler scheduler = MetricsRefreshScheduler.start(() -> {
            refreshThread.set(Thread.currentThread());
            ran.countDown();
        }, FAST)) {
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(refreshThread.get().getName()).isEqualTo("metrics-refresh");
        assertThat(refreshThread.get().isDaemon()).isTrue();
    }

    @Test
    void close_stops_refreshing() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        MetricsRefreshScheduler scheduler = MetricsRefreshScheduler.start(() -> {
            runs.incrementAndGet();
            ran.countDown();
        }, FAST);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();

        scheduler.close();
        int runsAtClose = runs.get();
        Thread.sleep(FAST.multipliedBy(5).toMillis());

        assertThat(runs.get()).isLessThanOrEqualTo(runsAtClose + 1);
    }
}
