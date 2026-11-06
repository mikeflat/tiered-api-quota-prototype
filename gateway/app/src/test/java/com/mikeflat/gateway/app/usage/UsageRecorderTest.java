package com.mikeflat.gateway.app.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class UsageRecorderTest {

    private static final Executor DIRECT = Runnable::run;
    private static final Executor ALWAYS_FULL = task -> {
        throw new RejectedExecutionException("queue full");
    };

    private static final QuotaSettings SETTINGS = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L),
            80);
    private static final UsageLimits BRONZE = new UsageLimits(100, 80);
    private static final UsageLimits GOLD = new UsageLimits(10_000, 8_000);
    private static final long UNKNOWN_TENANT = 99;

    private final TenantUsageLimits tenantUsageLimits = new TenantUsageLimits(
            new TenantRegistry(List.of(
                    new Tenant(1, "Acme Corp", Tier.BRONZE),
                    new Tenant(2, "Birch Labs", Tier.BRONZE),
                    new Tenant(3, "Cobalt Systems", Tier.BRONZE),
                    new Tenant(14, "Nimbus Cloud", Tier.GOLD))),
            SETTINGS);
    private final RecordingCounter counter = new RecordingCounter();
    private final List<RecordedUsage> notified = new ArrayList<>();
    private final RecordedUsageListener listener =
            (event, used, limits) -> notified.add(new RecordedUsage(event, used, limits));
    private final RecordingAlerts alerts = new RecordingAlerts();

    @Test
    void twenty_failures_raise_one_outage_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);
        recorder.submit(allowed(1));
        counter.goDown();

        for (int i = 0; i < 20; i++) {
            recorder.submit(allowed(2));
        }

        assertThat(alerts.raised).containsExactly("failing: redis down");
        assertThat(counter.calls).isEqualTo(21);
        assertThat(counter.recorded).containsExactly(allowed(1));
    }

    @Test
    void first_failure_after_start_raises_outage_alert() {
        counter.goDown();
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));

        assertThat(alerts.raised).containsExactly("failing: redis down");
    }

    @Test
    void recovery_raises_one_resume_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);
        counter.goDown();
        recorder.submit(allowed(1));
        recorder.submit(allowed(1));
        counter.comeBack();

        recorder.submit(allowed(2));
        recorder.submit(allowed(3));

        assertThat(alerts.raised).containsExactly("failing: redis down", "resumed");
    }

    @Test
    void failure_after_recovery_raises_new_outage_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);
        counter.goDown();
        recorder.submit(allowed(1));
        counter.comeBack();
        recorder.submit(allowed(2));
        counter.goDown();

        recorder.submit(allowed(3));

        assertThat(alerts.raised).containsExactly("failing: redis down", "resumed", "failing: redis down");
    }

    @Test
    void success_without_outage_raises_no_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(alerts.raised).isEmpty();
    }

    @Test
    void drops_raise_no_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, ALWAYS_FULL, listener, alerts);

        for (int i = 0; i < 20; i++) {
            recorder.submit(allowed(1));
        }

        assertThat(alerts.raised).isEmpty();
    }

    @Test
    void submit_never_calls_alerts() {
        List<Runnable> queued = new ArrayList<>();
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, queued::add, listener, alerts);
        counter.goDown();

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(alerts.raised).isEmpty();
        queued.forEach(Runnable::run);
        assertThat(alerts.raised).containsExactly("failing: redis down");
    }

    @Test
    void submitted_event_is_recorded() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));

        assertThat(counter.recorded).containsExactly(allowed(1));
    }

    @Test
    void rejected_submission_is_dropped() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, ALWAYS_FULL, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(counter.recorded).isEmpty();
        assertThat(counter.calls).isZero();
        assertThat(notified).isEmpty();
    }

    @Test
    void rejected_submission_does_not_throw() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, ALWAYS_FULL, listener, alerts);

        assertThatCode(() -> recorder.submit(allowed(1))).doesNotThrowAnyException();
    }

    @Test
    void failed_record_is_not_retried_and_next_event_still_recorded() {
        counter.failFor(allowed(1));
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(counter.recorded).containsExactly(allowed(2));
        assertThat(counter.calls).isEqualTo(2);
    }

    @Test
    void failed_record_does_not_reach_the_submitter() {
        counter.failFor(allowed(1));
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        assertThatCode(() -> recorder.submit(allowed(1))).doesNotThrowAnyException();
    }

    @Test
    void events_are_recorded_in_submission_order() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(3));
        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(counter.recorded).containsExactly(allowed(3), allowed(1), allowed(2));
    }

    @Test
    void listener_receives_event_and_used_after_record() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(notified)
                .containsExactly(new RecordedUsage(allowed(1), 1, BRONZE), new RecordedUsage(allowed(2), 2, BRONZE));
    }

    @Test
    void limits_of_current_tier_are_passed_to_counter_and_listener() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(14));

        assertThat(counter.limitsUsed).containsExactly(BRONZE, GOLD);
        assertThat(notified).extracting(RecordedUsage::limits).containsExactly(BRONZE, GOLD);
    }

    @Test
    void event_of_unknown_tenant_is_skipped() {
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(UNKNOWN_TENANT));

        assertThat(counter.calls).isZero();
        assertThat(notified).isEmpty();
    }

    @Test
    void failure_with_limits_resolved_reaches_counter_once() {
        counter.goDown();
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));

        assertThat(counter.limitsUsed).containsExactly(BRONZE);
        assertThat(notified).isEmpty();
        assertThat(counter.calls).isEqualTo(1);
    }

    @Test
    void listener_is_not_called_when_record_fails() {
        counter.failFor(allowed(1));
        UsageRecorder recorder = new UsageRecorder(counter, tenantUsageLimits, DIRECT, listener, alerts);

        recorder.submit(allowed(1));

        assertThat(notified).isEmpty();
    }

    private record RecordedUsage(UsageEvent event, long used, UsageLimits limits) {
    }

    private static UsageEvent allowed(long tenantId) {
        return new UsageEvent(tenantId, UsageOutcome.ALLOWED, 30_000_000L, 1_800_000_030L);
    }

    private static final class RecordingAlerts implements UsageRecordingAlerts {
        final List<String> raised = new ArrayList<>();

        @Override
        public void recordingFailing(UsageRecordingFailedException cause) {
            raised.add("failing: " + cause.getMessage());
        }

        @Override
        public void recordingResumed() {
            raised.add("resumed");
        }
    }

    private static final class RecordingCounter implements UsageCounter {
        final List<UsageEvent> recorded = new ArrayList<>();
        final List<UsageLimits> limitsUsed = new ArrayList<>();
        int calls;
        private final Set<UsageEvent> failing = new HashSet<>();
        private boolean down;

        void failFor(UsageEvent event) {
            failing.add(event);
        }

        void goDown() {
            down = true;
        }

        void comeBack() {
            down = false;
        }

        @Override
        public long record(UsageEvent event, UsageLimits limits) {
            calls++;
            limitsUsed.add(limits);
            if (down || failing.contains(event)) {
                throw new UsageRecordingFailedException("redis down", new IllegalStateException());
            }
            recorded.add(event);
            return recorded.size();
        }
    }
}
