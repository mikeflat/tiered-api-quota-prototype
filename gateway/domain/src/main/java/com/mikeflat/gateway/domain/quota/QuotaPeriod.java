package com.mikeflat.gateway.domain.quota;

import java.time.Instant;

public record QuotaPeriod(long index, Instant endsAt, long secondsLeft) {

    public QuotaPeriod {
        if (index < 0) {
            throw new IllegalArgumentException("period index must not be negative, got " + index);
        }
        if (endsAt == null) {
            throw new IllegalArgumentException("period end must not be null");
        }
        if (secondsLeft < 1) {
            throw new IllegalArgumentException("seconds left must be at least 1, got " + secondsLeft);
        }
    }
}
