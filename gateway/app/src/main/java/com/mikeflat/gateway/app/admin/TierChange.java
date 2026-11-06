package com.mikeflat.gateway.app.admin;

import com.mikeflat.gateway.domain.tenant.Tenant;

public sealed interface TierChange {

    record Changed(Tenant tenant, long limit) implements TierChange {
    }

    record TenantNotFound() implements TierChange {
    }
}
