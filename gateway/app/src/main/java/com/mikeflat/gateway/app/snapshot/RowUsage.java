package com.mikeflat.gateway.app.snapshot;

import com.mikeflat.gateway.domain.quota.TenantStatus;

public record RowUsage(long used, long rejected, TenantStatus status) {
}
