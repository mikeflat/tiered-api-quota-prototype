package com.mikeflat.gateway.app.snapshot;

public record TenantUsage(long used, long rejected) {

    public static final TenantUsage NONE = new TenantUsage(0, 0);
}
