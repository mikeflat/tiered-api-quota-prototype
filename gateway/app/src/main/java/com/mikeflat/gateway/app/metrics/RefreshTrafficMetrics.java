package com.mikeflat.gateway.app.metrics;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import java.time.Clock;
import java.time.Instant;

public final class RefreshTrafficMetrics {

    private final TrafficMetricsReader reader;
    private final LatestTrafficMetrics latest;
    private final QuotaSettings settings;
    private final Clock clock;

    public RefreshTrafficMetrics(
            TrafficMetricsReader reader, LatestTrafficMetrics latest, QuotaSettings settings, Clock clock) {
        this.reader = reader;
        this.latest = latest;
        this.settings = settings;
        this.clock = clock;
    }

    public void refresh() {
        Instant now = clock.instant();
        long epochSecond = now.getEpochSecond();
        try {
            latest.store(reader.read(settings.periodIndexAt(epochSecond), epochSecond - 1), now);
        } catch (MetricsReadFailedException redisUnavailable) {
            latest.markUnavailable();
        }
    }
}
