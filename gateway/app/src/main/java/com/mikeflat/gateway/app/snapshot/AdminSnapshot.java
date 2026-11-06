package com.mikeflat.gateway.app.snapshot;

import com.mikeflat.gateway.domain.quota.QuotaPeriod;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import java.util.Optional;

public record AdminSnapshot(QuotaPeriod period, Optional<TrafficMetrics> metrics, TenantRows tenants) {
}
