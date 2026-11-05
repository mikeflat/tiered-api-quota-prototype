package com.mikeflat.gateway.app.usage;

public final class SilentUsageRecordingAlerts implements UsageRecordingAlerts {

    @Override
    public void recordingFailing(UsageRecordingFailedException cause) {
    }

    @Override
    public void recordingResumed() {
    }
}
