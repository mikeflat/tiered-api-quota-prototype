package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;

public interface UsageCounter {

    long record(UsageEvent event, UsageLimits limits);
}
