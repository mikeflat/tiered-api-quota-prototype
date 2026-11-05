package com.mikeflat.gateway.app.usage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecordingOutageTest {

    private final RecordingOutage outage = new RecordingOutage();

    @Test
    void first_failure_starts_outage() {
        assertThat(outage.failed()).isTrue();
    }

    @Test
    void further_failures_do_not_start_another() {
        outage.failed();

        assertThat(outage.failed()).isFalse();
        assertThat(outage.failed()).isFalse();
    }

    @Test
    void success_ends_outage() {
        outage.failed();
        outage.failed();

        assertThat(outage.succeeded()).isTrue();
        assertThat(outage.succeeded()).isFalse();
    }

    @Test
    void success_without_outage_ends_nothing() {
        assertThat(outage.succeeded()).isFalse();
    }

    @Test
    void failure_after_recovery_starts_new_outage() {
        outage.failed();
        outage.succeeded();

        assertThat(outage.failed()).isTrue();
    }
}
