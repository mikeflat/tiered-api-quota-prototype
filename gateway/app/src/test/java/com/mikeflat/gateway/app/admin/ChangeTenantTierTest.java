package com.mikeflat.gateway.app.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeTenantTierTest {

    private static final QuotaSettings SETTINGS = new QuotaSettings(
            Duration.ofSeconds(60), Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L), 80);
    private static final long PERIOD = 30_000_000L;
    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final Tenant BLUEBIRD = new Tenant(2, "Bluebird Analytics", Tier.BRONZE);

    private final TenantRegistry registry = new TenantRegistry(List.of(ACME, BLUEBIRD));
    private final BlockedTenants blockedTenants = new BlockedTenants();
    private final ChangeTenantTier changeTenantTier = new ChangeTenantTier(registry, blockedTenants, SETTINGS);

    @Test
    void change_updates_registry_and_unblocks() {
        blockedTenants.block(1, PERIOD);

        TierChange change = changeTenantTier.change(1, Tier.GOLD);

        assertThat(change).isEqualTo(new TierChange.Changed(new Tenant(1, "Acme Corp", Tier.GOLD), 10_000));
        assertThat(registry.find(1)).map(Tenant::tier).contains(Tier.GOLD);
        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
    }

    @Test
    void unknown_tenant_is_not_found_and_nothing_changes() {
        blockedTenants.block(1, PERIOD);

        assertThat(changeTenantTier.change(99, Tier.GOLD)).isInstanceOf(TierChange.TenantNotFound.class);
        assertThat(registry.listByName()).containsExactly(ACME, BLUEBIRD);
        assertThat(blockedTenants.isBlocked(1, PERIOD)).isTrue();
    }

    @Test
    void same_tier_still_unblocks() {
        blockedTenants.block(1, PERIOD);

        TierChange change = changeTenantTier.change(1, Tier.BRONZE);

        assertThat(change).isEqualTo(new TierChange.Changed(ACME, 100));
        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
    }

    @Test
    void other_blocked_tenants_stay_blocked() {
        blockedTenants.block(1, PERIOD);
        blockedTenants.block(2, PERIOD);

        changeTenantTier.change(1, Tier.SILVER);

        assertThat(blockedTenants.isBlocked(2, PERIOD)).isTrue();
    }
}
