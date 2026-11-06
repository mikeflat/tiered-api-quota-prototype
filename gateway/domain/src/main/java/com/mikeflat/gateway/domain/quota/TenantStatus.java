package com.mikeflat.gateway.domain.quota;

public enum TenantStatus {
    OK,
    NEAR,
    OVER;

    public static TenantStatus of(long used, UsageLimits limits) {
        if (limits.reachedBy(used)) {
            return OVER;
        }
        if (used >= limits.nearBoundary()) {
            return NEAR;
        }
        return OK;
    }
}
