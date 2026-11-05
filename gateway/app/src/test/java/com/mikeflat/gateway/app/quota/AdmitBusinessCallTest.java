package com.mikeflat.gateway.app.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.Admission;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdmitBusinessCallTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);

    private final AdmitBusinessCall admitBusinessCall =
            new AdmitBusinessCall(new AdmissionPolicy(new TenantRegistry(List.of(ACME))));

    @Test
    void known_tenant_is_admitted() {
        assertThat(admitBusinessCall.admit(1)).isEqualTo(new Admission.Admitted(ACME));
    }

    @Test
    void unknown_tenant_is_unknown() {
        assertThat(admitBusinessCall.admit(2)).isInstanceOf(Admission.TenantUnknown.class);
    }
}
