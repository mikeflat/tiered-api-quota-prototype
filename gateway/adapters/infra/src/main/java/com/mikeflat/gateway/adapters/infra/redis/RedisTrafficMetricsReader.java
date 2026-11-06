package com.mikeflat.gateway.adapters.infra.redis;

import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.nearKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.overKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.periodTotalKey;
import static com.mikeflat.gateway.adapters.infra.redis.RedisKeys.secondKey;

import com.mikeflat.gateway.app.metrics.MetricsReadFailedException;
import com.mikeflat.gateway.app.metrics.TrafficMetricsReader;
import com.mikeflat.gateway.domain.quota.PeriodTotals;
import com.mikeflat.gateway.domain.quota.SecondTraffic;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisTrafficMetricsReader implements TrafficMetricsReader {

    public static final int SECONDS_SHOWN = 60;

    private final StringRedisTemplate template;

    public RedisTrafficMetricsReader(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public TrafficMetrics read(long periodIndex, long lastCompleteSecond) {
        List<Long> seconds = LongStream.rangeClosed(lastCompleteSecond - SECONDS_SHOWN + 1, lastCompleteSecond)
                .boxed()
                .toList();
        List<Object> replies;
        try {
            replies = template.executePipelined(readAllIn(periodIndex, seconds));
        } catch (DataAccessException redisFailure) {
            throw new MetricsReadFailedException("reading traffic metrics of period " + periodIndex + " failed",
                    redisFailure);
        }
        return toMetrics(seconds, replies);
    }

    private static SessionCallback<Object> readAllIn(long periodIndex, List<Long> seconds) {
        return new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> redis = (RedisOperations<String, String>) operations;
                redis.opsForValue().multiGet(secondKeys(UsageOutcome.ALLOWED, seconds));
                redis.opsForValue().multiGet(secondKeys(UsageOutcome.REJECTED, seconds));
                redis.opsForValue().multiGet(List.of(
                        periodTotalKey(UsageOutcome.ALLOWED, periodIndex),
                        periodTotalKey(UsageOutcome.REJECTED, periodIndex)));
                redis.opsForSet().size(nearKey(periodIndex));
                redis.opsForSet().size(overKey(periodIndex));
                return null;
            }
        };
    }

    private static List<String> secondKeys(UsageOutcome outcome, List<Long> seconds) {
        return seconds.stream().map(second -> secondKey(outcome, second)).toList();
    }

    @SuppressWarnings("unchecked")
    private static TrafficMetrics toMetrics(List<Long> seconds, List<Object> replies) {
        List<String> allowed = (List<String>) replies.get(0);
        List<String> rejected = (List<String>) replies.get(1);
        List<String> totals = (List<String>) replies.get(2);
        List<SecondTraffic> perSecond = new ArrayList<>(seconds.size());
        for (int i = 0; i < seconds.size(); i++) {
            perSecond.add(new SecondTraffic(seconds.get(i), countOf(allowed.get(i)), countOf(rejected.get(i))));
        }
        return new TrafficMetrics(
                perSecond,
                new PeriodTotals(countOf(totals.get(0)), countOf(totals.get(1))),
                (Long) replies.get(3),
                (Long) replies.get(4));
    }

    static long countOf(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }
}
