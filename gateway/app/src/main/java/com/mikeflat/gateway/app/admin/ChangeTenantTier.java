package com.mikeflat.gateway.app.admin;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;

public final class ChangeTenantTier {

    private static final TierChange NOT_FOUND = new TierChange.TenantNotFound();

    private final TenantRegistry registry;
    private final BlockedTenants blockedTenants;
    private final QuotaSettings settings;

    public ChangeTenantTier(TenantRegistry registry, BlockedTenants blockedTenants, QuotaSettings settings) {
        this.registry = registry;
        this.blockedTenants = blockedTenants;
        this.settings = settings;
    }

    public TierChange change(long tenantId, Tier tier) {
        return registry.changeTier(tenantId, tier)
                .map(this::unblock)
                .orElse(NOT_FOUND);
    }

    private TierChange unblock(Tenant changed) {
        blockedTenants.unblock(changed.id());
        return new TierChange.Changed(changed, settings.limitOf(changed.tier()));
    }
}
