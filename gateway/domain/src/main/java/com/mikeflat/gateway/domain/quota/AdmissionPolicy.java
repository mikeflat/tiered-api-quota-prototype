package com.mikeflat.gateway.domain.quota;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;

public final class AdmissionPolicy {

    private static final Admission UNKNOWN = new Admission.TenantUnknown();

    private final TenantRegistry registry;
    private final BlockedTenants blockedTenants;
    private final QuotaSettings settings;

    public AdmissionPolicy(TenantRegistry registry, BlockedTenants blockedTenants, QuotaSettings settings) {
        this.registry = registry;
        this.blockedTenants = blockedTenants;
        this.settings = settings;
    }

    public Admission admit(long tenantId, long epochSecond) {
        return registry.find(tenantId)
                .map(tenant -> decide(tenant, epochSecond))
                .orElse(UNKNOWN);
    }

    private Admission decide(Tenant tenant, long epochSecond) {
        if (blockedTenants.isBlocked(tenant.id(), settings.periodIndexAt(epochSecond))) {
            return new Admission.Blocked(tenant, settings.secondsUntilNextPeriod(epochSecond));
        }
        return new Admission.Admitted(tenant);
    }
}
