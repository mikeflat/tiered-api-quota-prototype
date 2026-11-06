package com.mikeflat.gateway.app.metrics;

import com.mikeflat.gateway.domain.quota.TrafficMetrics;

public interface TrafficMetricsReader {

    TrafficMetrics read(long periodIndex, long lastCompleteSecond);
}
