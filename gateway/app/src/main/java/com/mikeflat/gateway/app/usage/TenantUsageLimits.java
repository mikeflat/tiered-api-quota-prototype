package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import java.util.Optional;

public final class TenantUsageLimits {

    private final TenantRegistry registry;
    private final QuotaSettings settings;

    public TenantUsageLimits(TenantRegistry registry, QuotaSettings settings) {
        this.registry = registry;
        this.settings = settings;
    }

    public Optional<UsageLimits> of(long tenantId) {
        return registry.find(tenantId).map(tenant -> settings.limitsOf(tenant.tier()));
    }
}
