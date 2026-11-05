package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.UsageEvent;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

public final class UsageRecorder {

    private final UsageCounter counter;
    private final Executor writer;
    private final RecordedUsageListener listener;
    private final LongAdder recorded = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public UsageRecorder(UsageCounter counter, Executor writer, RecordedUsageListener listener) {
        this.counter = counter;
        this.writer = writer;
        this.listener = listener;
    }

    public void submit(UsageEvent event) {
        try {
            writer.execute(() -> apply(event));
        } catch (RejectedExecutionException queueFull) {
            dropped.increment();
        }
    }

    public UsageRecordingStats stats() {
        return new UsageRecordingStats(recorded.sum(), dropped.sum(), failed.sum());
    }

    private void apply(UsageEvent event) {
        long used;
        try {
            used = counter.record(event);
        } catch (UsageRecordingFailedException redisUnavailable) {
            failed.increment();
            return;
        }
        recorded.increment();
        listener.usageRecorded(event, used);
    }
}
