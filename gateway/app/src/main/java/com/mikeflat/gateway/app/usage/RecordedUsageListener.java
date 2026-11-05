package com.mikeflat.gateway.app.usage;

import com.mikeflat.gateway.domain.quota.UsageEvent;

public interface RecordedUsageListener {

    void usageRecorded(UsageEvent event, long used);
}
