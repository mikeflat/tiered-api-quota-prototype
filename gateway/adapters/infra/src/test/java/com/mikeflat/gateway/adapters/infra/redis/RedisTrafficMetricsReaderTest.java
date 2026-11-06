package com.mikeflat.gateway.adapters.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.app.metrics.MetricsReadFailedException;
import com.mikeflat.gateway.domain.quota.PeriodTotals;
import com.mikeflat.gateway.domain.quota.SecondTraffic;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
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
class RedisTrafficMetricsReaderTest {

    private static final long PERIOD = 30_000_000L;
    private static final long LAST_SECOND = PERIOD * 60 + 45;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisTrafficMetricsReader reader;

    @BeforeAll
    static void connect() {
        connectionFactory = RedisTestConnections.connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(connectionFactory);
        reader = new RedisTrafficMetricsReader(redis);
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
    void reads_sixty_seconds_oldest_first_with_zeros_for_gaps() {
        redis.opsForValue().set("traffic:allowed:" + LAST_SECOND, "3");
        redis.opsForValue().set("traffic:rejected:" + LAST_SECOND, "1");
        redis.opsForValue().set("traffic:allowed:" + (LAST_SECOND - 59), "7");
        redis.opsForValue().set("traffic:allowed:" + (LAST_SECOND - 60), "99");
        redis.opsForValue().set("traffic:allowed:" + (LAST_SECOND + 1), "99");

        TrafficMetrics metrics = reader.read(PERIOD, LAST_SECOND);

        assertThat(metrics.perSecond()).hasSize(60);
        assertThat(metrics.perSecond().getFirst()).isEqualTo(new SecondTraffic(LAST_SECOND - 59, 7, 0));
        assertThat(metrics.perSecond().getLast()).isEqualTo(new SecondTraffic(LAST_SECOND, 3, 1));
        assertThat(metrics.perSecond().get(49)).isEqualTo(new SecondTraffic(LAST_SECOND - 10, 0, 0));
        assertThat(metrics.perSecond()).extracting(SecondTraffic::second).isSorted();
    }

    @Test
    void reads_period_totals_and_set_sizes() {
        redis.opsForValue().set("traffic:total:allowed:" + PERIOD, "120");
        redis.opsForValue().set("traffic:total:rejected:" + PERIOD, "7");
        redis.opsForSet().add("quota:near:" + PERIOD, "3", "13");
        redis.opsForSet().add("quota:over:" + PERIOD, "1");
        redis.opsForValue().set("traffic:total:allowed:" + (PERIOD - 1), "500");
        redis.opsForSet().add("quota:over:" + (PERIOD - 1), "2", "4");

        TrafficMetrics metrics = reader.read(PERIOD, LAST_SECOND);

        assertThat(metrics.totals()).isEqualTo(new PeriodTotals(120, 7));
        assertThat(metrics.tenantsNear()).isEqualTo(2);
        assertThat(metrics.tenantsOver()).isEqualTo(1);
    }

    @Test
    void empty_period_reads_zeros() {
        TrafficMetrics metrics = reader.read(PERIOD, LAST_SECOND);

        assertThat(metrics.totals()).isEqualTo(new PeriodTotals(0, 0));
        assertThat(metrics.tenantsNear()).isZero();
        assertThat(metrics.tenantsOver()).isZero();
        assertThat(metrics.perSecond()).hasSize(60)
                .allSatisfy(second -> assertThat(second.allowed() + second.rejected()).isZero());
    }

    @Test
    void unreachable_redis_fails_with_metrics_read_failed() {
        LettuceConnectionFactory unreachable = RedisTestConnections.unreachable();
        try {
            RedisTrafficMetricsReader failing = new RedisTrafficMetricsReader(new StringRedisTemplate(unreachable));

            assertThatThrownBy(() -> failing.read(PERIOD, LAST_SECOND))
                    .isInstanceOf(MetricsReadFailedException.class)
                    .hasCauseInstanceOf(DataAccessException.class);
        } finally {
            unreachable.destroy();
        }
    }
}
