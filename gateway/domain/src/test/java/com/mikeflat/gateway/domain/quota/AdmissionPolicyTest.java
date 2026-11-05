package com.mikeflat.gateway.domain.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdmissionPolicyTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final Tenant NIMBUS = new Tenant(14, "Nimbus Cloud", Tier.GOLD);

    private final AdmissionPolicy policy = new AdmissionPolicy(new TenantRegistry(List.of(ACME, NIMBUS)));

    @Test
    void registered_id_is_admitted() {
        assertThat(policy.admit(14)).isEqualTo(new Admission.Admitted(NIMBUS));
    }

    @Test
    void unregistered_id_is_unknown() {
        assertThat(policy.admit(999)).isInstanceOf(Admission.TenantUnknown.class);
    }

    @Test
    void zero_is_unknown() {
        assertThat(policy.admit(0)).isInstanceOf(Admission.TenantUnknown.class);
    }
}
