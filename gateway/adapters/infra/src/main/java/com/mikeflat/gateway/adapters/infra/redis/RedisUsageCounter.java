package com.mikeflat.gateway.adapters.infra.redis;

import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisUsageCounter implements UsageCounter {

    public static final String RECORD_USAGE_SCRIPT = "redis/record-usage.lua";

    private final StringRedisTemplate template;
    private final RedisScript<Long> recordUsageScript;
    private final String ttlSeconds;

    public RedisUsageCounter(StringRedisTemplate template, RedisScript<Long> recordUsageScript, long ttlSeconds) {
        if (ttlSeconds < 1) {
            throw new IllegalArgumentException("usage key ttl must be at least 1 second, got " + ttlSeconds);
        }
        this.template = template;
        this.recordUsageScript = recordUsageScript;
        this.ttlSeconds = Long.toString(ttlSeconds);
    }

    public static String usedKey(long tenantId, long periodIndex) {
        return "quota:used:" + tenantId + ":" + periodIndex;
    }

    public static String rejectedKey(long tenantId, long periodIndex) {
        return "quota:rejected:" + tenantId + ":" + periodIndex;
    }

    @Override
    public long record(UsageEvent event) {
        List<String> keys = List.of(
                usedKey(event.tenantId(), event.periodIndex()),
                rejectedKey(event.tenantId(), event.periodIndex()));
        try {
            return template.execute(recordUsageScript, keys, scriptOutcome(event.outcome()), ttlSeconds);
        } catch (DataAccessException redisFailure) {
            throw new UsageRecordingFailedException(
                    "recording usage of tenant " + event.tenantId() + " in period " + event.periodIndex() + " failed",
                    redisFailure);
        }
    }

    private static String scriptOutcome(UsageOutcome outcome) {
        return switch (outcome) {
            case ALLOWED -> "allowed";
            case REJECTED -> "rejected";
        };
    }
}
