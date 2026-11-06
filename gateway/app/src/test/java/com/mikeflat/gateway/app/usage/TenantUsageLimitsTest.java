package com.mikeflat.gateway.app.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantUsageLimitsTest {

    private static final QuotaSettings SETTINGS = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L),
            80);

    private final TenantUsageLimits tenantUsageLimits = new TenantUsageLimits(
            new TenantRegistry(List.of(new Tenant(1, "Acme Corp", Tier.BRONZE), new Tenant(14, "Nimbus Cloud", Tier.GOLD))),
            SETTINGS);

    @Test
    void known_tenant_gets_limits_of_its_tier() {
        assertThat(tenantUsageLimits.of(1)).contains(new UsageLimits(100, 80));
        assertThat(tenantUsageLimits.of(14)).contains(new UsageLimits(10_000, 8_000));
    }

    @Test
    void unknown_tenant_has_no_limits() {
        assertThat(tenantUsageLimits.of(99)).isEmpty();
    }
}
