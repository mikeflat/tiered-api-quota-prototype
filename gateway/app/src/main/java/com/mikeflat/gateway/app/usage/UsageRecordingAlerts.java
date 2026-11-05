package com.mikeflat.gateway.app.usage;

public interface UsageRecordingAlerts {

    void recordingFailing(UsageRecordingFailedException cause);

    void recordingResumed();
}
