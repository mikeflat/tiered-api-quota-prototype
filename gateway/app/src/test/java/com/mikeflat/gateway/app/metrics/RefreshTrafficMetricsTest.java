package com.mikeflat.gateway.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.PeriodTotals;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RefreshTrafficMetricsTest {

    private static final QuotaSettings SETTINGS = new QuotaSettings(
            Duration.ofSeconds(60), Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L), 80);
    private static final Instant NOW = Instant.ofEpochSecond(6_000_045L, 300_000_000);
    private static final TrafficMetrics METRICS = new TrafficMetrics(List.of(), new PeriodTotals(3, 1), 0, 0);

    private final FakeReader reader = new FakeReader();
    private final LatestTrafficMetrics latest = new LatestTrafficMetrics();
    private final RefreshTrafficMetrics refresh =
            new RefreshTrafficMetrics(reader, latest, SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reads_current_period_and_last_complete_second() {
        refresh.refresh();

        assertThat(reader.calls).containsExactly(List.of(100_000L, 6_000_044L));
        assertThat(latest.current(NOW)).contains(METRICS);
    }

    @Test
    void failure_marks_unavailable() {
        refresh.refresh();
        reader.down = true;

        refresh.refresh();

        assertThat(latest.current(NOW)).isEmpty();
    }

    @Test
    void success_after_failure_restores_metrics() {
        reader.down = true;
        refresh.refresh();
        reader.down = false;

        refresh.refresh();

        assertThat(latest.current(NOW)).contains(METRICS);
    }

    private static final class FakeReader implements TrafficMetricsReader {
        final List<List<Long>> calls = new ArrayList<>();
        boolean down;

        @Override
        public TrafficMetrics read(long periodIndex, long lastCompleteSecond) {
            calls.add(List.of(periodIndex, lastCompleteSecond));
            if (down) {
                throw new MetricsReadFailedException("redis down", new IllegalStateException());
            }
            return METRICS;
        }
    }
}
