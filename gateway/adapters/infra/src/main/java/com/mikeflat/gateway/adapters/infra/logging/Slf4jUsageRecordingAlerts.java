package com.mikeflat.gateway.adapters.infra.logging;

import com.mikeflat.gateway.app.usage.UsageRecordingAlerts;
import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Slf4jUsageRecordingAlerts implements UsageRecordingAlerts {

    private static final Logger log = LoggerFactory.getLogger(Slf4jUsageRecordingAlerts.class);

    @Override
    public void recordingFailing(UsageRecordingFailedException failure) {
        log.warn("Redis unavailable, usage recording failing: no new tenants are blocked and counting is paused ({})",
                redisFailureMessage(failure));
    }

    @Override
    public void recordingResumed() {
        log.info("Redis available again, usage recording resumed");
    }

    private static String redisFailureMessage(UsageRecordingFailedException failure) {
        Throwable redisFailure = failure.getCause() != null ? failure.getCause() : failure;
        return redisFailure.getMessage();
    }
}
