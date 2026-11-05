package com.mikeflat.gateway.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantRegistryTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final Tenant KESTREL = new Tenant(11, "Kestrel Payments", Tier.SILVER);
    private static final Tenant NIMBUS = new Tenant(14, "Nimbus Cloud", Tier.GOLD);

    private final TenantRegistry registry = new TenantRegistry(List.of(ACME, KESTREL, NIMBUS));

    @Test
    void finds_tenant_by_id() {
        assertThat(registry.find(11)).contains(KESTREL);
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 0, -1})
    void unknown_id_is_not_found(long id) {
        assertThat(registry.find(id)).isEmpty();
    }

    @Test
    void lists_by_name_ignoring_case() {
        TenantRegistry names = new TenantRegistry(List.of(
                new Tenant(2, "beta", Tier.BRONZE),
                new Tenant(1, "Alpha", Tier.BRONZE),
                new Tenant(3, "Gamma", Tier.BRONZE)));

        assertThat(names.listByName()).extracting(Tenant::name).containsExactly("Alpha", "beta", "Gamma");
    }

    @Test
    void same_name_ordered_by_id_numerically() {
        TenantRegistry twins = new TenantRegistry(List.of(
                new Tenant(10, "Acme", Tier.BRONZE),
                new Tenant(9, "Acme", Tier.GOLD)));

        assertThat(twins.listByName()).extracting(Tenant::id).containsExactly(9L, 10L);
    }

    @Test
    void listing_is_a_copy() {
        List<Tenant> listing = registry.listByName();

        assertThatThrownBy(() -> listing.add(ACME)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.size()).isEqualTo(3);
    }

    @Test
    void registry_does_not_follow_later_changes_to_the_source_collection() {
        List<Tenant> source = new ArrayList<>(List.of(ACME));
        TenantRegistry copy = new TenantRegistry(source);

        source.add(NIMBUS);

        assertThat(copy.size()).isEqualTo(1);
        assertThat(copy.find(14)).isEmpty();
    }

    @Test
    void empty_collection_rejected() {
        assertThatThrownBy(() -> new TenantRegistry(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no tenants");
    }

    @Test
    void null_collection_rejected() {
        assertThatThrownBy(() -> new TenantRegistry(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no tenants");
    }

    @Test
    void duplicate_id_rejected_naming_it() {
        List<Tenant> tenants = List.of(ACME, KESTREL, new Tenant(1, "Another Acme", Tier.GOLD));

        assertThatThrownBy(() -> new TenantRegistry(tenants))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tenant id 1");
    }

    @Test
    void counts_by_tier_including_empty_tiers() {
        TenantRegistry bronzeOnly = new TenantRegistry(List.of(ACME, new Tenant(2, "B", Tier.BRONZE)));

        assertThat(registry.countByTier())
                .containsExactly(Map.entry(Tier.BRONZE, 1), Map.entry(Tier.SILVER, 1), Map.entry(Tier.GOLD, 1));
        assertThat(bronzeOnly.countByTier())
                .containsExactly(Map.entry(Tier.BRONZE, 2), Map.entry(Tier.SILVER, 0), Map.entry(Tier.GOLD, 0));
    }

    @Test
    void size_is_the_number_of_tenants() {
        assertThat(registry.size()).isEqualTo(3);
    }
}
