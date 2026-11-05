package com.mikeflat.gateway.app.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class UsageRecorderTest {

    private static final Executor DIRECT = Runnable::run;
    private static final Executor ALWAYS_FULL = task -> {
        throw new RejectedExecutionException("queue full");
    };

    private final RecordingCounter counter = new RecordingCounter();
    private final List<RecordedUsage> notified = new ArrayList<>();
    private final RecordedUsageListener listener = (event, used) -> notified.add(new RecordedUsage(event, used));
    private final RecordingAlerts alerts = new RecordingAlerts();

    @Test
    void twenty_failures_raise_one_outage_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);
        recorder.submit(allowed(1));
        counter.goDown();

        for (int i = 0; i < 20; i++) {
            recorder.submit(allowed(2));
        }

        assertThat(alerts.raised).containsExactly("failing: redis down");
        assertThat(recorder.stats()).isEqualTo(new UsageRecordingStats(1, 0, 20));
    }

    @Test
    void first_failure_after_start_raises_outage_alert() {
        counter.goDown();
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        recorder.submit(allowed(1));

        assertThat(alerts.raised).containsExactly("failing: redis down");
    }

    @Test
    void recovery_raises_one_resume_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);
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
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);
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
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(alerts.raised).isEmpty();
    }

    @Test
    void drops_raise_no_alert() {
        UsageRecorder recorder = new UsageRecorder(counter, ALWAYS_FULL, listener, alerts);

        for (int i = 0; i < 20; i++) {
            recorder.submit(allowed(1));
        }

        assertThat(alerts.raised).isEmpty();
    }

    @Test
    void submit_never_calls_alerts() {
        List<Runnable> queued = new ArrayList<>();
        UsageRecorder recorder = new UsageRecorder(counter, queued::add, listener, alerts);
        counter.goDown();

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(alerts.raised).isEmpty();
        queued.forEach(Runnable::run);
        assertThat(alerts.raised).containsExactly("failing: redis down");
    }

    @Test
    void submitted_event_is_recorded_and_counted() {
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        recorder.submit(allowed(1));

        assertThat(counter.recorded).containsExactly(allowed(1));
        assertThat(recorder.stats()).isEqualTo(new UsageRecordingStats(1, 0, 0));
    }

    @Test
    void rejected_submission_is_dropped_and_counted() {
        UsageRecorder recorder = new UsageRecorder(counter, ALWAYS_FULL, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(counter.recorded).isEmpty();
        assertThat(recorder.stats()).isEqualTo(new UsageRecordingStats(0, 2, 0));
    }

    @Test
    void rejected_submission_does_not_throw() {
        UsageRecorder recorder = new UsageRecorder(counter, ALWAYS_FULL, listener, alerts);

        assertThatCode(() -> recorder.submit(allowed(1))).doesNotThrowAnyException();
    }

    @Test
    void failed_record_is_counted_and_next_event_still_recorded() {
        counter.failFor(allowed(1));
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(counter.recorded).containsExactly(allowed(2));
        assertThat(recorder.stats()).isEqualTo(new UsageRecordingStats(1, 0, 1));
    }

    @Test
    void failed_record_does_not_reach_the_submitter() {
        counter.failFor(allowed(1));
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        assertThatCode(() -> recorder.submit(allowed(1))).doesNotThrowAnyException();
    }

    @Test
    void events_are_recorded_in_submission_order() {
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        recorder.submit(allowed(3));
        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(counter.recorded).containsExactly(allowed(3), allowed(1), allowed(2));
    }

    @Test
    void new_recorder_has_zero_stats() {
        assertThat(new UsageRecorder(counter, DIRECT, listener, alerts).stats()).isEqualTo(new UsageRecordingStats(0, 0, 0));
    }

    @Test
    void listener_receives_event_and_used_after_record() {
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        recorder.submit(allowed(1));
        recorder.submit(allowed(2));

        assertThat(notified).containsExactly(new RecordedUsage(allowed(1), 1), new RecordedUsage(allowed(2), 2));
    }

    @Test
    void listener_is_not_called_when_record_fails() {
        counter.failFor(allowed(1));
        UsageRecorder recorder = new UsageRecorder(counter, DIRECT, listener, alerts);

        recorder.submit(allowed(1));

        assertThat(notified).isEmpty();
    }

    private record RecordedUsage(UsageEvent event, long used) {
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
        public long record(UsageEvent event) {
            if (down || failing.contains(event)) {
                throw new UsageRecordingFailedException("redis down", new IllegalStateException());
            }
            recorded.add(event);
            return recorded.size();
        }
    }
}
