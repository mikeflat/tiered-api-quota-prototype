package com.mikeflat.gateway.adapters.api.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantIdHeaderTest {

    @Test
    void null_header_is_missing() {
        assertThat(TenantIdHeader.parse(null)).isInstanceOf(TenantIdHeader.Missing.class);
    }

    @Test
    void empty_header_is_missing() {
        assertThat(TenantIdHeader.parse("")).isInstanceOf(TenantIdHeader.Missing.class);
    }

    @Test
    void blank_header_is_missing() {
        assertThat(TenantIdHeader.parse("  \t")).isInstanceOf(TenantIdHeader.Missing.class);
    }

    @Test
    void decimal_id_is_present() {
        assertThat(TenantIdHeader.parse("14")).isEqualTo(new TenantIdHeader.Present(14));
    }

    @Test
    void eighteen_digits_are_present() {
        assertThat(TenantIdHeader.parse("999999999999999999"))
                .isEqualTo(new TenantIdHeader.Present(999_999_999_999_999_999L));
    }

    @Test
    void leading_zeros_are_accepted() {
        assertThat(TenantIdHeader.parse("01")).isEqualTo(new TenantIdHeader.Present(1));
    }

    @Test
    void zero_is_present_for_the_policy_to_judge() {
        assertThat(TenantIdHeader.parse("0")).isEqualTo(new TenantIdHeader.Present(0));
    }

    @Test
    void plus_sign_is_malformed() {
        assertThat(TenantIdHeader.parse("+1")).isInstanceOf(TenantIdHeader.Malformed.class);
    }

    @Test
    void nineteen_digits_are_malformed() {
        assertThat(TenantIdHeader.parse("1000000000000000001")).isInstanceOf(TenantIdHeader.Malformed.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme", "-1", "1.5", " 1", "1 ", "1e3", "99999999999999999999", "<script>", "١"})
    void non_decimal_value_is_malformed(String header) {
        assertThat(TenantIdHeader.parse(header)).isInstanceOf(TenantIdHeader.Malformed.class);
    }
}
