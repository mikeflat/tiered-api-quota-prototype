package com.mikeflat.gateway.app.usage;

public record UsageRecordingStats(long recorded, long dropped, long failed) {
}
