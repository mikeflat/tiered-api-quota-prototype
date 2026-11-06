package com.mikeflat.gateway.domain.quota;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantStatusTest {

    private static final UsageLimits BRONZE = new UsageLimits(100, 80);

    @Test
    void used_at_limit_is_over() {
        assertThat(TenantStatus.of(100, BRONZE)).isEqualTo(TenantStatus.OVER);
        assertThat(TenantStatus.of(101, BRONZE)).isEqualTo(TenantStatus.OVER);
    }

    @Test
    void used_at_near_boundary_is_near() {
        assertThat(TenantStatus.of(80, BRONZE)).isEqualTo(TenantStatus.NEAR);
        assertThat(TenantStatus.of(99, BRONZE)).isEqualTo(TenantStatus.NEAR);
    }

    @Test
    void used_below_near_boundary_is_ok() {
        assertThat(TenantStatus.of(79, BRONZE)).isEqualTo(TenantStatus.OK);
    }

    @Test
    void zero_used_is_ok() {
        assertThat(TenantStatus.of(0, BRONZE)).isEqualTo(TenantStatus.OK);
    }
}
