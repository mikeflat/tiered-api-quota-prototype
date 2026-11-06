package com.mikeflat.gateway.app.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.app.usage.SilentUsageRecordingAlerts;
import com.mikeflat.gateway.app.usage.TenantUsageLimits;
import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.domain.quota.Admission;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class QuotaOvershootTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final QuotaSettings BRONZE_LIMIT_FIVE = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 5L, Tier.SILVER, 50L, Tier.GOLD, 500L),
            80);
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_800_000_030L), ZoneOffset.UTC);

    private final PerTenantCounter counter = new PerTenantCounter();
    private final StalledWriter writer = new StalledWriter();
    private final AdmitBusinessCall admitBusinessCall = wire();

    private AdmitBusinessCall wire() {
        TenantRegistry registry = new TenantRegistry(List.of(ACME));
        BlockedTenants blockedTenants = new BlockedTenants();
        UsageRecorder recorder = new UsageRecorder(
                counter,
                new TenantUsageLimits(registry, BRONZE_LIMIT_FIVE),
                writer,
                new BlockTenantsOverLimit(BRONZE_LIMIT_FIVE, blockedTenants, CLOCK),
                new SilentUsageRecordingAlerts());
        return new AdmitBusinessCall(
                new AdmissionPolicy(registry, blockedTenants, BRONZE_LIMIT_FIVE), recorder, BRONZE_LIMIT_FIVE, CLOCK);
    }

    @Test
    void requests_pass_until_writer_applies_limit_event() {
        for (int i = 0; i < 8; i++) {
            assertThat(admitBusinessCall.admit(1)).isInstanceOf(Admission.Admitted.class);
        }

        writer.release();

        assertThat(counter.usedBy(1)).isEqualTo(8);
        assertThat(admitBusinessCall.admit(1)).isInstanceOf(Admission.Blocked.class);
    }

    @Test
    void tenant_is_blocked_once_limit_event_is_applied() {
        for (int i = 0; i < 5; i++) {
            assertThat(admitBusinessCall.admit(1)).isInstanceOf(Admission.Admitted.class);
            writer.release();
        }

        assertThat(admitBusinessCall.admit(1)).isInstanceOf(Admission.Blocked.class);
    }

    private static final class StalledWriter implements Executor {
        private final Queue<Runnable> held = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            held.add(task);
        }

        void release() {
            while (!held.isEmpty()) {
                held.remove().run();
            }
        }
    }

    private static final class PerTenantCounter implements UsageCounter {
        private final Map<Long, Long> used = new HashMap<>();

        @Override
        public long record(UsageEvent event, UsageLimits limits) {
            if (event.outcome() == UsageOutcome.ALLOWED) {
                used.merge(event.tenantId(), 1L, Long::sum);
            }
            return usedBy(event.tenantId());
        }

        long usedBy(long tenantId) {
            return used.getOrDefault(tenantId, 0L);
        }
    }
}
