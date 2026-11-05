package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.UsageEvent;

public interface UsageCounter {

    long record(UsageEvent event);
}
