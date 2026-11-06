package com.mikeflat.gateway.adapters.infra.redis;

import com.mikeflat.gateway.domain.quota.UsageOutcome;

public final class RedisKeys {

    private RedisKeys() {
    }

    public static String usedKey(long tenantId, long periodIndex) {
        return "quota:used:" + tenantId + ":" + periodIndex;
    }

    public static String rejectedKey(long tenantId, long periodIndex) {
        return "quota:rejected:" + tenantId + ":" + periodIndex;
    }

    public static String secondKey(UsageOutcome outcome, long epochSecond) {
        return "traffic:" + outcomeName(outcome) + ":" + epochSecond;
    }

    public static String periodTotalKey(UsageOutcome outcome, long periodIndex) {
        return "traffic:total:" + outcomeName(outcome) + ":" + periodIndex;
    }

    public static String nearKey(long periodIndex) {
        return "quota:near:" + periodIndex;
    }

    public static String overKey(long periodIndex) {
        return "quota:over:" + periodIndex;
    }

    static String outcomeName(UsageOutcome outcome) {
        return switch (outcome) {
            case ALLOWED -> "allowed";
            case REJECTED -> "rejected";
        };
    }
}
