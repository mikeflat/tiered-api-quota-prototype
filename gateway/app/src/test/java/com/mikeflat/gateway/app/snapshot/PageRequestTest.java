package com.mikeflat.gateway.app.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageRequestTest {

    @Test
    void defaults_are_page_zero_size_ten() {
        assertThat(PageRequest.defaults()).isEqualTo(new PageRequest(0, 10));
    }

    @Test
    void rejects_negative_page() {
        assertThatThrownBy(() -> new PageRequest(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void rejects_size_outside_one_to_hundred(int size) {
        assertThatThrownBy(() -> new PageRequest(0, size))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    @Test
    void offset_is_page_times_size() {
        assertThat(new PageRequest(1, 3).offset()).isEqualTo(3);
        assertThat(new PageRequest(Integer.MAX_VALUE, 100).offset()).isEqualTo(Integer.MAX_VALUE * 100L);
    }
}
