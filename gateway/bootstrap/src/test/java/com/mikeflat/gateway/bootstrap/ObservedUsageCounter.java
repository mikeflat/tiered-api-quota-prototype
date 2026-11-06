package com.mikeflat.gateway.bootstrap;

import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import java.util.concurrent.atomic.AtomicLong;

final class ObservedUsageCounter implements UsageCounter {

    private final UsageCounter delegate;
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    ObservedUsageCounter(UsageCounter delegate) {
        this.delegate = delegate;
    }

    @Override
    public long record(UsageEvent event, UsageLimits limits) {
        try {
            long used = delegate.record(event, limits);
            recorded.incrementAndGet();
            return used;
        } catch (UsageRecordingFailedException failure) {
            failed.incrementAndGet();
            throw failure;
        }
    }

    long recorded() {
        return recorded.get();
    }

    long failed() {
        return failed.get();
    }
}
