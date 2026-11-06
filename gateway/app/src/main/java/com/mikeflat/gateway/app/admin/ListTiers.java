package com.mikeflat.gateway.app.admin;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.util.Arrays;
import java.util.List;

public final class ListTiers {

    private final QuotaSettings settings;

    public ListTiers(QuotaSettings settings) {
        this.settings = settings;
    }

    public List<TierLimit> list() {
        return Arrays.stream(Tier.values())
                .map(tier -> new TierLimit(tier, settings.limitOf(tier)))
                .toList();
    }
}
