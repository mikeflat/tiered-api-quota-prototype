package com.mikeflat.gateway.app.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.domain.quota.Admission;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdmitBusinessCallTest {

    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);
    private static final QuotaSettings SIXTY_SECOND_PERIOD = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L),
            80);

    private final List<UsageEvent> recordedEvents = new ArrayList<>();

    private AdmitBusinessCall admitBusinessCallAt(long epochSecond) {
        UsageRecorder recorder = new UsageRecorder(
                event -> {
                    recordedEvents.add(event);
                    return recordedEvents.size();
                },
                Runnable::run);
        return new AdmitBusinessCall(
                new AdmissionPolicy(new TenantRegistry(List.of(ACME))),
                recorder,
                SIXTY_SECOND_PERIOD,
                Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    @Test
    void known_tenant_is_admitted() {
        assertThat(admitBusinessCallAt(1_800_000_030L).admit(1)).isEqualTo(new Admission.Admitted(ACME));
    }

    @Test
    void unknown_tenant_is_unknown() {
        assertThat(admitBusinessCallAt(1_800_000_030L).admit(2)).isInstanceOf(Admission.TenantUnknown.class);
    }

    @Test
    void admitted_call_submits_allowed_event_for_current_period() {
        admitBusinessCallAt(1_800_000_030L).admit(1);

        assertThat(recordedEvents)
                .containsExactly(new UsageEvent(1, UsageOutcome.ALLOWED, 30_000_000L, 1_800_000_030L));
    }

    @Test
    void admitted_call_on_boundary_submits_event_for_next_period() {
        admitBusinessCallAt(1_800_000_060L).admit(1);

        assertThat(recordedEvents).singleElement()
                .extracting(UsageEvent::periodIndex)
                .isEqualTo(30_000_001L);
    }

    @Test
    void each_admitted_call_submits_one_event() {
        AdmitBusinessCall admitBusinessCall = admitBusinessCallAt(1_800_000_030L);

        admitBusinessCall.admit(1);
        admitBusinessCall.admit(1);

        assertThat(recordedEvents).hasSize(2);
    }

    @Test
    void unknown_tenant_submits_no_event() {
        admitBusinessCallAt(1_800_000_030L).admit(2);

        assertThat(recordedEvents).isEmpty();
    }
}
