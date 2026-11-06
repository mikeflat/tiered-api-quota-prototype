package com.mikeflat.gateway.app.snapshot;

import com.mikeflat.gateway.domain.tenant.Tenant;
import java.util.Optional;

public record TenantRow(Tenant tenant, long limit, Optional<RowUsage> usage) {
}
