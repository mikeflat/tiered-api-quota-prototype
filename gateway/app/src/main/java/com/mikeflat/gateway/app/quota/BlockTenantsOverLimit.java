package com.mikeflat.gateway.app.quota;

import com.mikeflat.gateway.app.usage.RecordedUsageListener;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import java.time.Clock;

public final class BlockTenantsOverLimit implements RecordedUsageListener {

    private final TenantRegistry registry;
    private final QuotaSettings settings;
    private final BlockedTenants blockedTenants;
    private final Clock clock;

    public BlockTenantsOverLimit(
            TenantRegistry registry, QuotaSettings settings, BlockedTenants blockedTenants, Clock clock) {
        this.registry = registry;
        this.settings = settings;
        this.blockedTenants = blockedTenants;
        this.clock = clock;
    }

    @Override
    public void usageRecorded(UsageEvent event, long used) {
        if (event.outcome() != UsageOutcome.ALLOWED || !isOfCurrentPeriod(event)) {
            return;
        }
        registry.find(event.tenantId())
                .filter(tenant -> settings.reachesLimit(tenant.tier(), used))
                .ifPresent(tenant -> blockedTenants.block(tenant.id(), event.periodIndex()));
    }

    private boolean isOfCurrentPeriod(UsageEvent event) {
        return event.periodIndex() == settings.periodIndexAt(clock.instant().getEpochSecond());
    }
}
