package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.UsageEvent;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

public final class UsageRecorder {

    private final UsageCounter counter;
    private final Executor writer;
    private final LongAdder recorded = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public UsageRecorder(UsageCounter counter, Executor writer) {
        this.counter = counter;
        this.writer = writer;
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
        try {
            counter.record(event);
            recorded.increment();
        } catch (UsageRecordingFailedException redisUnavailable) {
            failed.increment();
        }
    }
}
