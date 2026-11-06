package com.mikeflat.gateway.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.PeriodTotals;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatestTrafficMetricsTest {

    private static final Instant READ_AT = Instant.ofEpochSecond(1_800_000_030L);
    private static final TrafficMetrics METRICS = new TrafficMetrics(List.of(), new PeriodTotals(120, 7), 2, 1);

    private final LatestTrafficMetrics latest = new LatestTrafficMetrics();

    @Test
    void empty_before_first_read() {
        assertThat(latest.current(READ_AT)).isEmpty();
    }

    @Test
    void fresh_metrics_are_returned() {
        latest.store(METRICS, READ_AT);

        assertThat(latest.current(READ_AT)).contains(METRICS);
        assertThat(latest.current(READ_AT.plusSeconds(3))).contains(METRICS);
    }

    @Test
    void metrics_older_than_three_seconds_are_unavailable() {
        latest.store(METRICS, READ_AT);

        assertThat(latest.current(READ_AT.plusSeconds(3).plusMillis(1))).isEmpty();
    }

    @Test
    void failed_read_marks_unavailable() {
        latest.store(METRICS, READ_AT);

        latest.markUnavailable();

        assertThat(latest.current(READ_AT)).isEmpty();
    }
}
