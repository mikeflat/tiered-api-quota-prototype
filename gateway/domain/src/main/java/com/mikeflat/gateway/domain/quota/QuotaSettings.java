package com.mikeflat.gateway.domain.quota;

import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record QuotaSettings(Duration period, Map<Tier, Long> limits, int nearThresholdPercent) {

    public QuotaSettings {
        if (period == null) {
            throw new IllegalArgumentException("quota period must not be null");
        }
        if (period.getNano() != 0) {
            throw new IllegalArgumentException("quota period must be whole seconds, got " + period);
        }
        if (period.getSeconds() < 1) {
            throw new IllegalArgumentException(
                    "quota period must be at least 1 second, got " + period.getSeconds() + " s");
        }
        if (limits == null) {
            throw new IllegalArgumentException("quota limits must not be null");
        }
        EnumMap<Tier, Long> copy = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            Long limit = limits.get(tier);
            if (limit == null) {
                throw new IllegalArgumentException("quota limit for tier " + tier + " is missing");
            }
            if (limit < 1) {
                throw new IllegalArgumentException(
                        "quota limit for tier " + tier + " must be at least 1, got " + limit);
            }
            copy.put(tier, limit);
        }
        limits = Collections.unmodifiableMap(copy);
        if (nearThresholdPercent < 1 || nearThresholdPercent > 99) {
            throw new IllegalArgumentException(
                    "near threshold must be 1..99 percent, got " + nearThresholdPercent);
        }
    }

    public long periodSeconds() {
        return period.getSeconds();
    }

    public long periodIndexAt(long epochSecond) {
        return Math.floorDiv(epochSecond, periodSeconds());
    }

    public long secondsUntilNextPeriod(long epochSecond) {
        long nextPeriodStart = Math.multiplyExact(periodIndexAt(epochSecond) + 1, periodSeconds());
        return nextPeriodStart - epochSecond;
    }

    public QuotaPeriod periodAt(Instant now) {
        long epochSecond = now.getEpochSecond();
        long index = periodIndexAt(epochSecond);
        Instant endsAt = Instant.ofEpochSecond(Math.multiplyExact(index + 1, periodSeconds()));
        return new QuotaPeriod(index, endsAt, secondsUntilNextPeriod(epochSecond));
    }

    public long limitOf(Tier tier) {
        return limits.get(tier);
    }

    public UsageLimits limitsOf(Tier tier) {
        long limit = limitOf(tier);
        return new UsageLimits(limit, nearBoundary(limit));
    }

    public long nearBoundary(long limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        long scaled = Math.multiplyExact(limit, (long) nearThresholdPercent);
        return Math.addExact(scaled, 99) / 100;
    }
}
