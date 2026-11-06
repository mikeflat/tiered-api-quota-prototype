package com.mikeflat.gateway.domain.quota;

public record UsageLimits(long limit, long nearBoundary) {

    public UsageLimits {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        if (nearBoundary < 1 || nearBoundary > limit) {
            throw new IllegalArgumentException(
                    "near boundary must be 1.." + limit + ", got " + nearBoundary);
        }
    }

    public boolean reachedBy(long used) {
        return used >= limit;
    }
}
