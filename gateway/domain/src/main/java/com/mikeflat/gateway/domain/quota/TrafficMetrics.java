package com.mikeflat.gateway.domain.quota;

import java.util.List;

public record TrafficMetrics(List<SecondTraffic> perSecond, PeriodTotals totals, long tenantsNear, long tenantsOver) {

    public TrafficMetrics {
        perSecond = List.copyOf(perSecond);
        if (totals == null) {
            throw new IllegalArgumentException("period totals must not be null");
        }
    }
}
