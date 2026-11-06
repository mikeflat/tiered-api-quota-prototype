package com.mikeflat.gateway.app.quota;

import com.mikeflat.gateway.app.usage.RecordedUsageListener;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import java.time.Clock;

public final class BlockTenantsOverLimit implements RecordedUsageListener {

    private final QuotaSettings settings;
    private final BlockedTenants blockedTenants;
    private final Clock clock;

    public BlockTenantsOverLimit(QuotaSettings settings, BlockedTenants blockedTenants, Clock clock) {
        this.settings = settings;
        this.blockedTenants = blockedTenants;
        this.clock = clock;
    }

    @Override
    public void usageRecorded(UsageEvent event, long used, UsageLimits limits) {
        if (event.outcome() == UsageOutcome.ALLOWED && isOfCurrentPeriod(event) && limits.reachedBy(used)) {
            blockedTenants.block(event.tenantId(), event.periodIndex());
        }
    }

    private boolean isOfCurrentPeriod(UsageEvent event) {
        return event.periodIndex() == settings.periodIndexAt(clock.instant().getEpochSecond());
    }
}
