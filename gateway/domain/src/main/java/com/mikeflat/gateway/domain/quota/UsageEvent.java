package com.mikeflat.gateway.domain.quota;

import com.mikeflat.gateway.domain.tenant.Tenant;
import java.time.Instant;

public record UsageEvent(long tenantId, UsageOutcome outcome, long periodIndex, long epochSecond) {

    public UsageEvent {
        if (tenantId < 1) {
            throw new IllegalArgumentException("usage event tenant id must be a positive integer, got " + tenantId);
        }
        if (outcome == null) {
            throw new IllegalArgumentException("usage event outcome must not be null");
        }
        if (periodIndex < 0) {
            throw new IllegalArgumentException("usage event period index must not be negative, got " + periodIndex);
        }
    }

    public static UsageEvent allowedAt(Tenant tenant, Instant arrival, QuotaSettings settings) {
        long epochSecond = arrival.getEpochSecond();
        return new UsageEvent(tenant.id(), UsageOutcome.ALLOWED, settings.periodIndexAt(epochSecond), epochSecond);
    }
}
