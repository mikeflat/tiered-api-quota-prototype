package com.mikeflat.gateway.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TenantTest {

    @Test
    void holds_values() {
        Tenant tenant = new Tenant(1, "Acme Corp", Tier.BRONZE);

        assertThat(tenant.id()).isEqualTo(1);
        assertThat(tenant.name()).isEqualTo("Acme Corp");
        assertThat(tenant.tier()).isEqualTo(Tier.BRONZE);
    }

    @Test
    void largest_long_id_accepted() {
        assertThat(new Tenant(Long.MAX_VALUE, "Acme", Tier.GOLD).id()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void non_positive_id_rejected_naming_it(long id) {
        assertThatThrownBy(() -> new Tenant(id, "Acme Corp", Tier.BRONZE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id")
                .hasMessageContaining(Long.toString(id));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void blank_name_rejected(String name) {
        assertThatThrownBy(() -> new Tenant(1, name, Tier.BRONZE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void null_tier_rejected() {
        assertThatThrownBy(() -> new Tenant(1, "Acme Corp", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier");
    }

    @Test
    void with_tier_returns_copy_with_new_tier() {
        Tenant bronze = new Tenant(1, "Acme Corp", Tier.BRONZE);

        Tenant gold = bronze.withTier(Tier.GOLD);

        assertThat(gold).isEqualTo(new Tenant(1, "Acme Corp", Tier.GOLD));
        assertThat(bronze.tier()).isEqualTo(Tier.BRONZE);
    }

    @Test
    void with_null_tier_rejected() {
        Tenant tenant = new Tenant(1, "Acme Corp", Tier.BRONZE);

        assertThatThrownBy(() -> tenant.withTier(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier");
    }
}
