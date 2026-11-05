package com.mikeflat.gateway.app.quota;

import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.domain.quota.Admission;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import java.time.Clock;

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
        Admission admission = policy.admit(tenantId);
        if (admission instanceof Admission.Admitted(var tenant)) {
            usageRecorder.submit(UsageEvent.allowedAt(tenant, clock.instant(), settings));
        }
        return admission;
    }
}
