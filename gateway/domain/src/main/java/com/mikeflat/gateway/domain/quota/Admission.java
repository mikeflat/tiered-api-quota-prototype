package com.mikeflat.gateway.domain.quota;

import com.mikeflat.gateway.domain.tenant.Tenant;

public sealed interface Admission {

    record Admitted(Tenant tenant) implements Admission {
    }

    record TenantUnknown() implements Admission {
    }
}
