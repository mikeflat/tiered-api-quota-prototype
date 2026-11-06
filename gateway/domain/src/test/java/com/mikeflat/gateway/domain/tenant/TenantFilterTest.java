package com.mikeflat.gateway.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantFilterTest {

    private static final Tenant MERIDIAN = new Tenant(13, "Meridian Bank", Tier.SILVER);

    @Test
    void no_filter_matches_every_tenant() {
        assertThat(TenantFilter.none().matches(MERIDIAN)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bank", "BANK", "Meridian", "an B", "meridian bank"})
    void name_contains_ignores_case(String text) {
        assertThat(TenantFilter.none().withNameContaining(text).matches(MERIDIAN)).isTrue();
    }

    @Test
    void name_not_contained_does_not_match() {
        assertThat(TenantFilter.none().withNameContaining("acme").matches(MERIDIAN)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blank_name_means_no_name_filter(String text) {
        TenantFilter filter = TenantFilter.none().withNameContaining(text);

        assertThat(filter.nameContains()).isEmpty();
        assertThat(filter.matches(MERIDIAN)).isTrue();
    }

    @Test
    void id_must_be_equal() {
        assertThat(TenantFilter.none().withId(13).matches(MERIDIAN)).isTrue();
        assertThat(TenantFilter.none().withId(1).matches(MERIDIAN)).isFalse();
    }

    @Test
    void tier_must_be_equal() {
        assertThat(TenantFilter.none().withTier(Tier.SILVER).matches(MERIDIAN)).isTrue();
        assertThat(TenantFilter.none().withTier(Tier.GOLD).matches(MERIDIAN)).isFalse();
    }

    @Test
    void all_given_filters_must_match() {
        TenantFilter filter = new TenantFilter(Optional.of("bank"), Optional.of(13L), Optional.of(Tier.GOLD));

        assertThat(filter.matches(MERIDIAN)).isFalse();
        assertThat(filter.withTier(Tier.SILVER).matches(MERIDIAN)).isTrue();
    }
}
