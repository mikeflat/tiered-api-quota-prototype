package com.mikeflat.gateway.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.mikeflat.gateway.app.usage.TenantUsageLimits;
import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.app.usage.UsageRecordingAlerts;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UsageWriterExecutorTest {

    private static final Duration PROMPTLY = Duration.ofMillis(500);

    private final BlockingCounter counter = new BlockingCounter();
    private final ThreadPoolExecutor executor = UsageWriterExecutor.create(2);
    private final UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits(), executor, (event, used, limits) -> {
    }, mock(UsageRecordingAlerts.class));

    private static TenantUsageLimits tenantUsageLimits() {
        List<Tenant> tenants = LongStream.rangeClosed(1, 103)
                .mapToObj(id -> new Tenant(id, "Tenant " + id, Tier.BRONZE))
                .toList();
        QuotaSettings settings = new QuotaSettings(
                Duration.ofSeconds(60), Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L), 80);
        return new TenantUsageLimits(new TenantRegistry(tenants), settings);
    }

    @AfterEach
    void stopWriter() {
        counter.release();
        executor.shutdownNow();
    }

    @Test
    void capacity_two_holds_one_in_flight_and_two_queued() throws InterruptedException {
        occupyWriter();

        recorder.submit(event(2));
        recorder.submit(event(3));
        recorder.submit(event(4));

        assertThat(executor.getQueue()).hasSize(2);

        counter.release();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.recordedTenants).containsExactly(1L, 2L, 3L);
    }

    @Test
    void submission_beyond_capacity_is_dropped_without_blocking() throws InterruptedException {
        occupyWriter();
        recorder.submit(event(2));
        recorder.submit(event(3));

        assertTimeoutPreemptively(PROMPTLY, () -> {
            for (long tenantId = 4; tenantId < 104; tenantId++) {
                recorder.submit(event(tenantId));
            }
        });

        assertThat(executor.getQueue()).hasSize(2);
        counter.release();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.recordedTenants).containsExactly(1L, 2L, 3L);
    }

    @Test
    void events_run_on_usage_writer_thread() throws InterruptedException {
        occupyWriter();

        assertThat(counter.writerThread.getName()).isEqualTo("usage-writer");
        assertThat(counter.writerThread.isDaemon()).isTrue();
    }

    @Test
    void shutdown_now_returns_promptly_while_counter_hangs() throws InterruptedException {
        occupyWriter();
        recorder.submit(event(2));
        recorder.submit(event(3));

        List<Runnable> discarded = assertTimeoutPreemptively(PROMPTLY, executor::shutdownNow);

        assertThat(discarded).hasSize(2);
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.recordedTenants).isEmpty();
    }

    private void occupyWriter() throws InterruptedException {
        recorder.submit(event(1));
        assertThat(counter.started.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static UsageEvent event(long tenantId) {
        return new UsageEvent(tenantId, UsageOutcome.ALLOWED, 30_000_000L, 1_800_000_030L);
    }

    private static final class BlockingCounter implements UsageCounter {
        final CountDownLatch started = new CountDownLatch(1);
        final List<Long> recordedTenants = new CopyOnWriteArrayList<>();
        private final CountDownLatch released = new CountDownLatch(1);
        volatile Thread writerThread;

        void release() {
            released.countDown();
        }

        @Override
        public long record(UsageEvent event, UsageLimits limits) {
            writerThread = Thread.currentThread();
            started.countDown();
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
            recordedTenants.add(event.tenantId());
            return recordedTenants.size();
        }
    }
}
