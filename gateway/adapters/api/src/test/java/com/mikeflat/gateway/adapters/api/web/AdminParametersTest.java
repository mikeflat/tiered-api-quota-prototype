package com.mikeflat.gateway.adapters.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.app.snapshot.PageRequest;
import com.mikeflat.gateway.app.snapshot.SnapshotQuery;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.tenant.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminParametersTest {

    @ParameterizedTest
    @ValueSource(strings = {"silver", "SILVER", "Silver", "sIlVeR"})
    void tier_is_case_insensitive(String value) {
        assertThat(AdminParameters.optionalTier(value)).contains(Tier.SILVER);
        assertThat(AdminParameters.requiredTier(value)).isEqualTo(Tier.SILVER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blank_means_absent(String value) {
        assertThat(AdminParameters.optionalTier(value)).isEmpty();
        assertThat(AdminParameters.optionalTier(null)).isEmpty();
        assertThat(AdminParameters.optionalStatus(value)).isEmpty();
        assertThat(AdminParameters.snapshotQuery(value, null, value, value, null, null))
                .isEqualTo(SnapshotQuery.firstPage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "OKAY", "1"})
    void unknown_status_is_bad_request(String value) {
        assertBadRequest(() -> AdminParameters.optionalStatus(value));
    }

    @ParameterizedTest
    @CsvSource({"ok, OK", "Near, NEAR", "OVER, OVER"})
    void known_status_ignores_case(String value, TenantStatus expected) {
        assertThat(AdminParameters.optionalStatus(value)).contains(expected);
    }

    @Test
    void unknown_tier_is_bad_request() {
        assertBadRequest(() -> AdminParameters.optionalTier("Platinum"));
        assertBadRequest(() -> AdminParameters.requiredTier("Platinum"));
    }

    @Test
    void missing_required_tier_is_bad_request() {
        assertBadRequest(() -> AdminParameters.requiredTier(null));
        assertBadRequest(() -> AdminParameters.requiredTier(" "));
    }

    @Test
    void non_positive_id_is_bad_request() {
        assertBadRequest(() -> AdminParameters.tenantId(0));
        assertBadRequest(() -> AdminParameters.snapshotQuery(null, 0L, null, null, null, null));
    }

    @Test
    void paging_out_of_range_is_bad_request() {
        assertBadRequest(() -> AdminParameters.pageRequest(-1, 10));
        assertBadRequest(() -> AdminParameters.pageRequest(0, 0));
        assertBadRequest(() -> AdminParameters.pageRequest(0, 101));
        assertThat(AdminParameters.pageRequest(null, null)).isEqualTo(PageRequest.defaults());
    }

    private static void assertBadRequest(Runnable parse) {
        assertThatThrownBy(parse::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
