package com.mikeflat.gateway.domain.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BlockedTenantsTest {

    private static final long PERIOD = 30_000_000L;

    private final BlockedTenants blockedTenants = new BlockedTenants();

    @Test
    void nothing_is_blocked_initially() {
        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
        assertThat(blockedTenants.isBlocked(1, 0)).isFalse();
    }

    @Test
    void blocked_tenant_is_blocked_in_its_period() {
        blockedTenants.block(1, PERIOD);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isTrue();
    }

    @Test
    void other_tenants_stay_unblocked() {
        blockedTenants.block(1, PERIOD);

        assertThat(blockedTenants.isBlocked(2, PERIOD)).isFalse();
    }

    @Test
    void blocked_tenant_is_not_blocked_in_next_period() {
        blockedTenants.block(1, PERIOD);

        assertThat(blockedTenants.isBlocked(1, PERIOD + 1)).isFalse();
    }

    @Test
    void block_in_new_period_discards_previous_blocks() {
        blockedTenants.block(1, PERIOD);

        blockedTenants.block(2, PERIOD + 1);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
        assertThat(blockedTenants.isBlocked(1, PERIOD + 1)).isFalse();
        assertThat(blockedTenants.isBlocked(2, PERIOD + 1)).isTrue();
    }

    @Test
    void block_for_older_period_is_ignored() {
        blockedTenants.block(2, PERIOD + 1);

        blockedTenants.block(1, PERIOD);

        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
        assertThat(blockedTenants.isBlocked(1, PERIOD + 1)).isFalse();
        assertThat(blockedTenants.isBlocked(2, PERIOD + 1)).isTrue();
    }

    @Test
    void readers_see_blocks_made_by_another_thread() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            blockedTenants.block(1, PERIOD);
            blocked.countDown();
        });

        writer.start();

        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(blockedTenants.isBlocked(1, PERIOD)).isTrue();
        writer.join();
    }
}
