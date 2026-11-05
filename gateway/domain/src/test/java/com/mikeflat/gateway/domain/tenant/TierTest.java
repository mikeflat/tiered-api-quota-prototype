package com.mikeflat.gateway.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TierTest {

    @Test
    void canonical_names() {
        assertThat(Tier.BRONZE.displayName()).isEqualTo("Bronze");
        assertThat(Tier.SILVER.displayName()).isEqualTo("Silver");
        assertThat(Tier.GOLD.displayName()).isEqualTo("Gold");
        assertThat(Tier.GOLD).hasToString("Gold");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gold", "GOLD", "Gold", "gOlD"})
    void parse_ignores_case(String value) {
        assertThat(Tier.parse(value)).isEqualTo(Tier.GOLD);
    }

    @Test
    void parse_every_canonical_name() {
        for (Tier tier : Tier.values()) {
            assertThat(Tier.parse(tier.displayName())).isEqualTo(tier);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Platinum", "", " Gold", "gold "})
    void unknown_value_rejected_naming_it(String value) {
        assertThatThrownBy(() -> Tier.parse(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + value + "'");
    }

    @Test
    void null_rejected() {
        assertThatThrownBy(() -> Tier.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }
}
