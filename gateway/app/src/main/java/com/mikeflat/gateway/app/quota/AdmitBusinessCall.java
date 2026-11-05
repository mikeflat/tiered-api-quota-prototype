package com.mikeflat.gateway.app.quota;

import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.domain.quota.Admission;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import java.time.Clock;
import java.time.Instant;

public final class AdmitBusinessCall {

    private final AdmissionPolicy policy;
    private final UsageRecorder usageRecorder;
    private final QuotaSettings settings;
    private final Clock clock;

    public AdmitBusinessCall(
            AdmissionPolicy policy, UsageRecorder usageRecorder, QuotaSettings settings, Clock clock) {
        this.policy = policy;
        this.usageRecorder = usageRecorder;
        this.settings = settings;
        this.clock = clock;
    }

    public Admission admit(long tenantId) {
        Instant arrival = clock.instant();
        Admission admission = policy.admit(tenantId, arrival.getEpochSecond());
        switch (admission) {
            case Admission.Admitted admitted ->
                    usageRecorder.submit(UsageEvent.allowedAt(admitted.tenant(), arrival, settings));
            case Admission.Blocked blocked ->
                    usageRecorder.submit(UsageEvent.rejectedAt(blocked.tenant(), arrival, settings));
            case Admission.TenantUnknown unknown -> {
            }
        }
        return admission;
    }
}
