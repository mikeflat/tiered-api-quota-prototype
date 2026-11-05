package com.mikeflat.gateway.app.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockTenantsOverLimitTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final Tenant IRONWOOD = new Tenant(9, "Ironwood Retail", Tier.SILVER);
    private static final QuotaSettings SIXTY_SECOND_PERIOD = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 5L, Tier.SILVER, 50L, Tier.GOLD, 500L),
            80);
    private static final long NOW = 1_800_000_030L;
    private static final long PERIOD = 30_000_000L;

    private final BlockedTenants blockedTenants = new BlockedTenants();

    private BlockTenantsOverLimit blockTenantsOverLimitAt(long epochSecond) {
        return new BlockTenantsOverLimit(
                new TenantRegistry(List.of(ACME, IRONWOOD)),
                SIXTY_SECOND_PERIOD,
                blockedTenants,
                Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    @Test
    void allowed_event_reaching_limit_blocks_tenant() {
        blockTenantsOverLimitAt(NOW).usageRecorded(event(1, UsageOutcome.ALLOWED, PERIOD), 5);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isTrue();
    }

    @Test
    void allowed_event_below_limit_does_not_block() {
        blockTenantsOverLimitAt(NOW).usageRecorded(event(1, UsageOutcome.ALLOWED, PERIOD), 4);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
    }

    @Test
    void limit_follows_tenant_tier() {
        BlockTenantsOverLimit blockTenantsOverLimit = blockTenantsOverLimitAt(NOW);

        blockTenantsOverLimit.usageRecorded(event(1, UsageOutcome.ALLOWED, PERIOD), 5);
        blockTenantsOverLimit.usageRecorded(event(9, UsageOutcome.ALLOWED, PERIOD), 5);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isTrue();
        assertThat(blockedTenants.isBlocked(9, PERIOD)).isFalse();
    }

    @Test
    void rejected_event_never_blocks() {
        blockTenantsOverLimitAt(NOW).usageRecorded(event(1, UsageOutcome.REJECTED, PERIOD), 100);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
    }

    @Test
    void event_of_past_period_does_not_block() {
        blockTenantsOverLimitAt(NOW + 60).usageRecorded(event(1, UsageOutcome.ALLOWED, PERIOD), 5);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
        assertThat(blockedTenants.isBlocked(1, PERIOD + 1)).isFalse();
    }

    private static UsageEvent event(long tenantId, UsageOutcome outcome, long periodIndex) {
        return new UsageEvent(tenantId, outcome, periodIndex, periodIndex * 60 + 30);
    }
}
