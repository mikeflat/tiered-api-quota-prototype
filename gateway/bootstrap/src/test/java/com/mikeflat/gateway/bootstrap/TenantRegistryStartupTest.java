package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
class TenantRegistryStartupTest {

    @Autowired
    private TenantRegistry registry;

    @Test
    void seed_holds_fifteen_tenants_with_unique_ids() {
        assertThat(registry.size()).isEqualTo(15);
        Set<Long> ids = registry.listByName().stream().map(Tenant::id).collect(Collectors.toSet());
        assertThat(ids).hasSize(15);
    }

    @Test
    void seed_tier_split() {
        assertThat(registry.countByTier())
                .containsExactly(Map.entry(Tier.BRONZE, 8), Map.entry(Tier.SILVER, 5), Map.entry(Tier.GOLD, 2));
    }

    @Test
    void looks_up_seeded_tenant() {
        assertThat(registry.find(1)).contains(new Tenant(1, "Acme Corp", Tier.BRONZE));
    }

    @Test
    void logs_tenants_and_quota_settings(CapturedOutput output) {
        String tenantsFile = System.getenv("TENANTS_FILE");

        assertThat(output.getOut())
                .containsOnlyOnce("Loaded 15 tenants (Bronze 8, Silver 5, Gold 2) from " + tenantsFile)
                .containsOnlyOnce(
                        "Quota settings: period 60 s, limits Bronze 100, Silver 1000, Gold 10000, near threshold 80 %");
    }
}
