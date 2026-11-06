package com.mikeflat.gateway.app.quota;

import com.mikeflat.gateway.app.usage.RecordedUsageListener;
import com.mikeflat.gateway.app.usage.TenantUsageLimits;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import java.time.Clock;

public final class BlockTenantsOverLimit implements RecordedUsageListener {

    private final QuotaSettings settings;
    private final BlockedTenants blockedTenants;
    private final TenantUsageLimits tenantUsageLimits;
    private final Clock clock;

    public BlockTenantsOverLimit(
            QuotaSettings settings, BlockedTenants blockedTenants, TenantUsageLimits tenantUsageLimits, Clock clock) {
        this.settings = settings;
        this.blockedTenants = blockedTenants;
        this.tenantUsageLimits = tenantUsageLimits;
        this.clock = clock;
    }

    @Override
    public void usageRecorded(UsageEvent event, long used, UsageLimits limits) {
        if (event.outcome() == UsageOutcome.ALLOWED && isOfCurrentPeriod(event) && limits.reachedBy(used)) {
            blockedTenants.blockIf(
                    event.tenantId(), event.periodIndex(), () -> currentLimitReachedBy(event.tenantId(), used));
        }
    }

    private boolean currentLimitReachedBy(long tenantId, long used) {
        return tenantUsageLimits.of(tenantId).map(current -> current.reachedBy(used)).orElse(false);
    }

    private boolean isOfCurrentPeriod(UsageEvent event) {
        return event.periodIndex() == settings.periodIndexAt(clock.instant().getEpochSecond());
    }
}
