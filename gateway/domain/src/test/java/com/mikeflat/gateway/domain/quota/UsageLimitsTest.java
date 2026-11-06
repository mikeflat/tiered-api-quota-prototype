package com.mikeflat.gateway.domain.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsageLimitsTest {

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejects_limit_below_one(long limit) {
        assertThatThrownBy(() -> new UsageLimits(limit, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void rejects_near_boundary_above_limit() {
        assertThatThrownBy(() -> new UsageLimits(10, 11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("near boundary");
    }

    @Test
    void rejects_near_boundary_below_one() {
        assertThatThrownBy(() -> new UsageLimits(10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("near boundary");
    }

    @Test
    void near_boundary_equal_to_limit_accepted() {
        assertThat(new UsageLimits(1, 1).nearBoundary()).isEqualTo(1);
    }

    @Test
    void used_at_limit_reaches_it() {
        UsageLimits limits = new UsageLimits(100, 80);

        assertThat(limits.reachedBy(100)).isTrue();
        assertThat(limits.reachedBy(101)).isTrue();
    }

    @Test
    void used_below_limit_does_not_reach_it() {
        assertThat(new UsageLimits(100, 80).reachedBy(99)).isFalse();
    }
}
