package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class UsageRecorder {

    private final UsageCounter counter;
    private final TenantUsageLimits tenantUsageLimits;
    private final Executor writer;
    private final RecordedUsageListener listener;
    private final UsageRecordingAlerts alerts;
    private final RecordingOutage outage = new RecordingOutage();

    public UsageRecorder(
            UsageCounter counter,
            TenantUsageLimits tenantUsageLimits,
            Executor writer,
            RecordedUsageListener listener,
            UsageRecordingAlerts alerts) {
        this.counter = counter;
        this.tenantUsageLimits = tenantUsageLimits;
        this.writer = writer;
        this.listener = listener;
        this.alerts = alerts;
    }

    public void submit(UsageEvent event) {
        try {
            writer.execute(() -> apply(event));
        } catch (RejectedExecutionException queueFullSoEventIsDropped) {
            return;
        }
    }

    private void apply(UsageEvent event) {
        tenantUsageLimits.of(event.tenantId()).ifPresent(limits -> apply(event, limits));
    }

    private void apply(UsageEvent event, UsageLimits limits) {
        long used;
        try {
            used = counter.record(event, limits);
        } catch (UsageRecordingFailedException redisUnavailable) {
            if (outage.failed()) {
                alerts.recordingFailing(redisUnavailable);
            }
            return;
        }
        if (outage.succeeded()) {
            alerts.recordingResumed();
        }
        listener.usageRecorded(event, used, limits);
    }
}
