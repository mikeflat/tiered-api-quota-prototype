package com.mikeflat.gateway.domain.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuotaSettingsTest {

    private static final Map<Tier, Long> DEFAULT_LIMITS =
            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L);

    private static QuotaSettings settings(Duration period, Map<Tier, Long> limits, int threshold) {
        return new QuotaSettings(period, limits, threshold);
    }

    private final QuotaSettings defaults = settings(Duration.ofSeconds(60), DEFAULT_LIMITS, 80);

    @Test
    void exposes_values() {
        assertThat(defaults.period()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.periodSeconds()).isEqualTo(60);
        assertThat(defaults.nearThresholdPercent()).isEqualTo(80);
    }

    @Test
    void limit_of_every_tier() {
        assertThat(defaults.limitOf(Tier.BRONZE)).isEqualTo(100);
        assertThat(defaults.limitOf(Tier.SILVER)).isEqualTo(1_000);
        assertThat(defaults.limitOf(Tier.GOLD)).isEqualTo(10_000);
    }

    @Test
    void limits_are_copied_and_unmodifiable() {
        Map<Tier, Long> source = new HashMap<>(DEFAULT_LIMITS);
        QuotaSettings copy = settings(Duration.ofSeconds(60), source, 80);

        source.put(Tier.GOLD, 1L);

        assertThat(copy.limitOf(Tier.GOLD)).isEqualTo(10_000);
        assertThatThrownBy(() -> copy.limits().put(Tier.GOLD, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void one_second_period_accepted() {
        assertThat(settings(Duration.ofSeconds(1), DEFAULT_LIMITS, 80).periodSeconds()).isEqualTo(1);
    }

    @Test
    void one_day_period_accepted() {
        assertThat(settings(Duration.ofDays(1), DEFAULT_LIMITS, 80).periodSeconds()).isEqualTo(86_400);
    }

    @Test
    void period_below_one_second_rejected() {
        assertThatThrownBy(() -> settings(Duration.ZERO, DEFAULT_LIMITS, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        assertThatThrownBy(() -> settings(Duration.ofSeconds(-5), DEFAULT_LIMITS, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
    }

    @Test
    void fractional_period_rejected() {
        assertThatThrownBy(() -> settings(Duration.ofMillis(1_500), DEFAULT_LIMITS, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
    }

    @Test
    void null_period_rejected() {
        assertThatThrownBy(() -> settings(null, DEFAULT_LIMITS, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -5})
    void limit_below_one_rejected_naming_tier(long limit) {
        Map<Tier, Long> limits = new EnumMap<>(DEFAULT_LIMITS);
        limits.put(Tier.SILVER, limit);

        assertThatThrownBy(() -> settings(Duration.ofSeconds(60), limits, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Silver")
                .hasMessageContaining(Long.toString(limit));
    }

    @Test
    void missing_tier_rejected() {
        Map<Tier, Long> limits = Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L);

        assertThatThrownBy(() -> settings(Duration.ofSeconds(60), limits, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gold");
    }

    @Test
    void null_limit_value_rejected() {
        Map<Tier, Long> limits = new EnumMap<>(DEFAULT_LIMITS);
        limits.put(Tier.BRONZE, null);

        assertThatThrownBy(() -> settings(Duration.ofSeconds(60), limits, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bronze");
    }

    @Test
    void null_limits_rejected() {
        assertThatThrownBy(() -> settings(Duration.ofSeconds(60), null, 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 99})
    void threshold_in_range_accepted(int threshold) {
        assertThat(settings(Duration.ofSeconds(60), DEFAULT_LIMITS, threshold).nearThresholdPercent())
                .isEqualTo(threshold);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100, -1, 101})
    void threshold_out_of_range_rejected(int threshold) {
        assertThatThrownBy(() -> settings(Duration.ofSeconds(60), DEFAULT_LIMITS, threshold))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold")
                .hasMessageContaining(Integer.toString(threshold));
    }

    @ParameterizedTest
    @CsvSource({
        "100, 80, 80",
        "7, 80, 6",
        "10000, 80, 8000",
        "1, 80, 1",
        "1000, 99, 990",
        "3, 1, 1",
    })
    void near_boundary_is_smallest_count_reaching_threshold(long limit, int threshold, long expected) {
        QuotaSettings custom = settings(Duration.ofSeconds(60), DEFAULT_LIMITS, threshold);

        assertThat(custom.nearBoundary(limit)).isEqualTo(expected);
    }

    @Test
    void period_index_of_second_inside_period() {
        assertThat(defaults.periodIndexAt(1_800_000_030L)).isEqualTo(30_000_000L);
        assertThat(defaults.periodIndexAt(1_800_000_059L)).isEqualTo(30_000_000L);
    }

    @Test
    void period_index_changes_on_boundary() {
        assertThat(defaults.periodIndexAt(1_800_000_000L)).isEqualTo(30_000_000L);
        assertThat(defaults.periodIndexAt(1_800_000_060L)).isEqualTo(30_000_001L);
    }

    @Test
    void period_index_for_one_day_period() {
        QuotaSettings daily = settings(Duration.ofDays(1), DEFAULT_LIMITS, 80);

        assertThat(daily.periodIndexAt(86_399L)).isEqualTo(0L);
        assertThat(daily.periodIndexAt(86_400L)).isEqualTo(1L);
        assertThat(daily.periodIndexAt(1_800_000_030L)).isEqualTo(20_833L);
    }

    @Test
    void period_index_at_epoch_start_is_zero() {
        assertThat(defaults.periodIndexAt(0L)).isZero();
    }

    @Test
    void seconds_until_next_period_in_middle_of_period() {
        assertThat(defaults.secondsUntilNextPeriod(1_800_000_030L)).isEqualTo(30);
    }

    @Test
    void seconds_until_next_period_in_last_second_is_one() {
        assertThat(defaults.secondsUntilNextPeriod(1_800_000_059L)).isEqualTo(1);
    }

    @Test
    void seconds_until_next_period_on_boundary_is_whole_period() {
        assertThat(defaults.secondsUntilNextPeriod(1_800_000_060L)).isEqualTo(60);
    }

    @Test
    void used_equal_to_limit_reaches_limit() {
        assertThat(defaults.reachesLimit(Tier.BRONZE, 100)).isTrue();
        assertThat(defaults.reachesLimit(Tier.BRONZE, 101)).isTrue();
    }

    @Test
    void used_below_limit_does_not_reach_limit() {
        assertThat(defaults.reachesLimit(Tier.BRONZE, 99)).isFalse();
        assertThat(defaults.reachesLimit(Tier.SILVER, 100)).isFalse();
    }

    @Test
    void near_boundary_overflow_rejected() {
        assertThatThrownBy(() -> defaults.nearBoundary(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void near_boundary_of_non_positive_limit_rejected() {
        assertThatThrownBy(() -> defaults.nearBoundary(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
