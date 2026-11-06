package com.mikeflat.gateway.app.snapshot;

import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.tenant.TenantFilter;
import java.util.Objects;
import java.util.Optional;

public record SnapshotQuery(TenantFilter filter, Optional<TenantStatus> status, PageRequest page) {

    public SnapshotQuery {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(page, "page");
    }

    public static SnapshotQuery firstPage() {
        return new SnapshotQuery(TenantFilter.none(), Optional.empty(), PageRequest.defaults());
    }
}
