package com.mikeflat.gateway.domain.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsageEventTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final QuotaSettings SIXTY_SECOND_PERIOD = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L),
            80);

    @Test
    void allowed_event_captures_period_and_second() {
        UsageEvent event = UsageEvent.allowedAt(ACME, Instant.ofEpochSecond(1_800_000_030L), SIXTY_SECOND_PERIOD);

        assertThat(event).isEqualTo(new UsageEvent(1, UsageOutcome.ALLOWED, 30_000_000L, 1_800_000_030L));
    }

    @Test
    void event_at_boundary_belongs_to_next_period() {
        UsageEvent event = UsageEvent.allowedAt(ACME, Instant.ofEpochSecond(1_800_000_060L), SIXTY_SECOND_PERIOD);

        assertThat(event.periodIndex()).isEqualTo(30_000_001L);
    }

    @Test
    void sub_second_part_of_arrival_is_ignored() {
        UsageEvent event = UsageEvent.allowedAt(
                ACME, Instant.ofEpochSecond(1_800_000_059L, 999_999_999L), SIXTY_SECOND_PERIOD);

        assertThat(event.epochSecond()).isEqualTo(1_800_000_059L);
        assertThat(event.periodIndex()).isEqualTo(30_000_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void non_positive_tenant_id_is_rejected(long tenantId) {
        assertThatThrownBy(() -> new UsageEvent(tenantId, UsageOutcome.ALLOWED, 1, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant id")
                .hasMessageContaining(Long.toString(tenantId));
    }

    @Test
    void negative_period_index_is_rejected() {
        assertThatThrownBy(() -> new UsageEvent(1, UsageOutcome.ALLOWED, -1, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period index");
    }

    @Test
    void missing_outcome_is_rejected() {
        assertThatThrownBy(() -> new UsageEvent(1, null, 1, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }
}
