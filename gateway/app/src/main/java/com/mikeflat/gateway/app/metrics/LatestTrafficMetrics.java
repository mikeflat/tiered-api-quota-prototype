package com.mikeflat.gateway.app.metrics;

import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class LatestTrafficMetrics {

    public static final Duration MAX_AGE = Duration.ofSeconds(3);

    private record Reading(TrafficMetrics metrics, Instant readAt) {
    }

    private final AtomicReference<Optional<Reading>> latest = new AtomicReference<>(Optional.empty());

    public void store(TrafficMetrics metrics, Instant readAt) {
        latest.set(Optional.of(new Reading(metrics, readAt)));
    }

    public void markUnavailable() {
        latest.set(Optional.empty());
    }

    public Optional<TrafficMetrics> current(Instant now) {
        return latest.get()
                .filter(reading -> !isOlderThanMaxAge(reading, now))
                .map(Reading::metrics);
    }

    private static boolean isOlderThanMaxAge(Reading reading, Instant now) {
        return Duration.between(reading.readAt(), now).compareTo(MAX_AGE) > 0;
    }
}
