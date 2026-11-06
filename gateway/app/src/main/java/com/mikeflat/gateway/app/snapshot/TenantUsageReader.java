package com.mikeflat.gateway.app.snapshot;

import com.mikeflat.gateway.domain.quota.TenantStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface TenantUsageReader {

    Map<Long, TenantUsage> usageOf(long periodIndex, List<Long> tenantIds);

    Set<Long> flaggedTenants(long periodIndex, Set<TenantStatus> statuses);
}
