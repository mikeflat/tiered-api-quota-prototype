package com.mikeflat.gateway.adapters.infra.redis;

import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.nearKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.overKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.rejectedKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.usedKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisTrafficMetricsReader.countOf;

import com.mikeflat.gateway.app.snapshot.TenantUsage;
import com.mikeflat.gateway.app.snapshot.TenantUsageReader;
import com.mikeflat.gateway.app.snapshot.UsageReadFailedException;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisTenantUsageReader implements TenantUsageReader {

    private final StringRedisTemplate template;

    public RedisTenantUsageReader(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public Map<Long, TenantUsage> usageOf(long periodIndex, List<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(tenantIds.size() * 2);
        for (long tenantId : tenantIds) {
            keys.add(usedKey(tenantId, periodIndex));
            keys.add(rejectedKey(tenantId, periodIndex));
        }
        List<String> values = readOrFail(() -> template.opsForValue().multiGet(keys), periodIndex);
        Map<Long, TenantUsage> usage = new LinkedHashMap<>();
        for (int i = 0; i < tenantIds.size(); i++) {
            usage.put(tenantIds.get(i), new TenantUsage(countOf(values.get(2 * i)), countOf(values.get(2 * i + 1))));
        }
        return usage;
    }

    @Override
    public Set<Long> flaggedTenants(long periodIndex, Set<TenantStatus> statuses) {
        List<String> setKeys = new ArrayList<>(2);
        if (statuses.contains(TenantStatus.NEAR)) {
            setKeys.add(nearKey(periodIndex));
        }
        if (statuses.contains(TenantStatus.OVER)) {
            setKeys.add(overKey(periodIndex));
        }
        if (setKeys.isEmpty()) {
            return Set.of();
        }
        Set<String> members = readOrFail(() -> setKeys.size() == 1
                ? template.opsForSet().members(setKeys.getFirst())
                : template.opsForSet().union(setKeys), periodIndex);
        return members.stream().map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
    }

    private static <T> T readOrFail(Supplier<T> read, long periodIndex) {
        try {
            return read.get();
        } catch (DataAccessException redisFailure) {
            throw new UsageReadFailedException("reading tenant usage of period " + periodIndex + " failed",
                    redisFailure);
        }
    }
}
