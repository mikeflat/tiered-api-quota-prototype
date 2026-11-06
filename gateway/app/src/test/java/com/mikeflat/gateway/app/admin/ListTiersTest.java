package com.mikeflat.gateway.app.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ListTiersTest {

    @Test
    void lists_three_tiers_in_order_with_configured_limits() {
        QuotaSettings settings = new QuotaSettings(
                Duration.ofSeconds(60), Map.of(Tier.BRONZE, 100L, Tier.SILVER, 250L, Tier.GOLD, 10_000L), 80);

        assertThat(new ListTiers(settings).list()).containsExactly(
                new TierLimit(Tier.BRONZE, 100),
                new TierLimit(Tier.SILVER, 250),
                new TierLimit(Tier.GOLD, 10_000));
    }
}
