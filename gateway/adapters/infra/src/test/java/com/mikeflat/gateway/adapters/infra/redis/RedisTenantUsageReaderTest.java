package com.mikeflat.gateway.adapters.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.app.snapshot.TenantUsage;
import com.mikeflat.gateway.app.snapshot.UsageReadFailedException;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisTenantUsageReaderTest {

    private static final long PERIOD = 30_000_000L;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisTenantUsageReader reader;

    @BeforeAll
    static void connect() {
        connectionFactory = RedisTestConnections.connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(connectionFactory);
        reader = new RedisTenantUsageReader(redis);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void emptyDatabase() {
        RedisTestConnections.flush(redis);
    }

    @Test
    void reads_used_and_rejected_for_requested_tenants() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "85");
        redis.opsForValue().set("quota:rejected:1:" + PERIOD, "4");
        redis.opsForValue().set("quota:used:3:" + PERIOD, "12");
        redis.opsForValue().set("quota:used:2:" + PERIOD, "50");
        redis.opsForValue().set("quota:used:1:" + (PERIOD - 1), "99");

        Map<Long, TenantUsage> usage = reader.usageOf(PERIOD, List.of(1L, 3L));

        assertThat(usage).containsExactly(
                Map.entry(1L, new TenantUsage(85, 4)),
                Map.entry(3L, new TenantUsage(12, 0)));
    }

    @Test
    void absent_counters_read_as_zero() {
        assertThat(reader.usageOf(PERIOD, List.of(7L))).containsExactly(Map.entry(7L, TenantUsage.NONE));
        assertThat(reader.usageOf(PERIOD, List.of())).isEmpty();
    }

    @Test
    void near_members_are_read() {
        redis.opsForSet().add("quota:near:" + PERIOD, "3", "13");
        redis.opsForSet().add("quota:over:" + PERIOD, "1");

        assertThat(reader.flaggedTenants(PERIOD, Set.of(TenantStatus.NEAR))).containsExactlyInAnyOrder(3L, 13L);
        assertThat(reader.flaggedTenants(PERIOD, Set.of(TenantStatus.OVER))).containsExactly(1L);
        assertThat(reader.flaggedTenants(PERIOD + 1, Set.of(TenantStatus.NEAR))).isEmpty();
    }

    @Test
    void near_and_over_union_is_read() {
        redis.opsForSet().add("quota:near:" + PERIOD, "3", "13");
        redis.opsForSet().add("quota:over:" + PERIOD, "1");

        assertThat(reader.flaggedTenants(PERIOD, Set.of(TenantStatus.NEAR, TenantStatus.OVER)))
                .containsExactlyInAnyOrder(1L, 3L, 13L);
    }

    @Test
    void unreachable_redis_fails_with_usage_read_failed() {
        LettuceConnectionFactory unreachable = RedisTestConnections.unreachable();
        try {
            RedisTenantUsageReader failing = new RedisTenantUsageReader(new StringRedisTemplate(unreachable));

            assertThatThrownBy(() -> failing.usageOf(PERIOD, List.of(1L)))
                    .isInstanceOf(UsageReadFailedException.class)
                    .hasCauseInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> failing.flaggedTenants(PERIOD, Set.of(TenantStatus.OVER)))
                    .isInstanceOf(UsageReadFailedException.class);
            assertThatThrownBy(() -> failing.flaggedTenants(PERIOD, Set.of(TenantStatus.NEAR, TenantStatus.OVER)))
                    .isInstanceOf(UsageReadFailedException.class);
        } finally {
            unreachable.destroy();
        }
    }
}
