package com.mikeflat.gateway.domain.quota;

import com.mikeflat.gateway.domain.tenant.TenantRegistry;

public final class AdmissionPolicy {

    private static final Admission UNKNOWN = new Admission.TenantUnknown();

    private final TenantRegistry registry;

    public AdmissionPolicy(TenantRegistry registry) {
        this.registry = registry;
    }

    public Admission admit(long tenantId) {
        return registry.find(tenantId)
                .<Admission>map(Admission.Admitted::new)
                .orElse(UNKNOWN);
    }
}
