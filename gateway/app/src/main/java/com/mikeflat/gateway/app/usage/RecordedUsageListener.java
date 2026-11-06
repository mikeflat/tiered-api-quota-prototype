package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;

public interface RecordedUsageListener {

    void usageRecorded(UsageEvent event, long used, UsageLimits limits);
}
