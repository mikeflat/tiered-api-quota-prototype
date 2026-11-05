package com.mikeflat.gateway.domain.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdmissionPolicyTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final Tenant NIMBUS = new Tenant(14, "Nimbus Cloud", Tier.GOLD);
    private static final QuotaSettings SIXTY_SECOND_PERIOD = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L),
            80);
    private static final long ARRIVAL = 1_800_000_030L;
    private static final long PERIOD = 30_000_000L;

    private final BlockedTenants blockedTenants = new BlockedTenants();
    private final AdmissionPolicy policy = new AdmissionPolicy(
            new TenantRegistry(List.of(ACME, NIMBUS)), blockedTenants, SIXTY_SECOND_PERIOD);

    @Test
    void registered_id_is_admitted() {
        assertThat(policy.admit(14, ARRIVAL)).isEqualTo(new Admission.Admitted(NIMBUS));
    }

    @Test
    void unregistered_id_is_unknown() {
        assertThat(policy.admit(999, ARRIVAL)).isInstanceOf(Admission.TenantUnknown.class);
    }

    @Test
    void zero_is_unknown() {
        assertThat(policy.admit(0, ARRIVAL)).isInstanceOf(Admission.TenantUnknown.class);
    }

    @Test
    void unblocked_known_tenant_is_admitted() {
        blockedTenants.block(14, PERIOD);

        assertThat(policy.admit(1, ARRIVAL)).isEqualTo(new Admission.Admitted(ACME));
    }

    @Test
    void blocked_tenant_is_blocked_with_retry_after() {
        blockedTenants.block(1, PERIOD);

        assertThat(policy.admit(1, ARRIVAL)).isEqualTo(new Admission.Blocked(ACME, 30));
        assertThat(policy.admit(1, 1_800_000_059L)).isEqualTo(new Admission.Blocked(ACME, 1));
    }

    @Test
    void tenant_blocked_in_previous_period_is_admitted() {
        blockedTenants.block(1, PERIOD);

        assertThat(policy.admit(1, 1_800_000_060L)).isEqualTo(new Admission.Admitted(ACME));
    }

    @Test
    void unknown_tenant_is_unknown_even_if_id_was_blocked() {
        blockedTenants.block(999, PERIOD);

        assertThat(policy.admit(999, ARRIVAL)).isInstanceOf(Admission.TenantUnknown.class);
    }
}
