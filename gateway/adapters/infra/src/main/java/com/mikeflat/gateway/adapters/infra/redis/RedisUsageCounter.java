package com.mikeflat.gateway.adapters.infra.redis;

import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.nearKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.outcomeName;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.overKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.periodTotalKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.rejectedKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.secondKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.usedKey;

import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisUsageCounter implements UsageCounter {

    public static final String RECORD_USAGE_SCRIPT = "redis/record-usage.lua";
    public static final long SECOND_KEY_TTL_SECONDS = 120;

    private final StringRedisTemplate template;
    private final RedisScript<Long> recordUsageScript;
    private final String periodTtlSeconds;

    public RedisUsageCounter(StringRedisTemplate template, RedisScript<Long> recordUsageScript, long periodTtlSeconds) {
        if (periodTtlSeconds < 1) {
            throw new IllegalArgumentException("usage key ttl must be at least 1 second, got " + periodTtlSeconds);
        }
        this.template = template;
        this.recordUsageScript = recordUsageScript;
        this.periodTtlSeconds = Long.toString(periodTtlSeconds);
    }

    @Override
    public long record(UsageEvent event, UsageLimits limits) {
        List<String> keys = List.of(
                usedKey(event.tenantId(), event.periodIndex()),
                rejectedKey(event.tenantId(), event.periodIndex()),
                secondKey(event.outcome(), event.epochSecond()),
                periodTotalKey(event.outcome(), event.periodIndex()),
                nearKey(event.periodIndex()),
                overKey(event.periodIndex()));
        try {
            return template.execute(
                    recordUsageScript,
                    keys,
                    outcomeName(event.outcome()),
                    periodTtlSeconds,
                    Long.toString(SECOND_KEY_TTL_SECONDS),
                    Long.toString(limits.limit()),
                    Long.toString(limits.nearBoundary()),
                    Long.toString(event.tenantId()));
        } catch (DataAccessException redisFailure) {
            throw new UsageRecordingFailedException(
                    "recording usage of tenant " + event.tenantId() + " in period " + event.periodIndex() + " failed",
                    redisFailure);
        }
    }
}
