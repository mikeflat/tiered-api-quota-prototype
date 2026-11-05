package com.mikeflat.gateway.app.quota;

import com.mikeflat.gateway.domain.quota.Admission;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;

public final class AdmitBusinessCall {

    private final AdmissionPolicy policy;

    public AdmitBusinessCall(AdmissionPolicy policy) {
        this.policy = policy;
    }

    public Admission admit(long tenantId) {
        return policy.admit(tenantId);
    }
}
