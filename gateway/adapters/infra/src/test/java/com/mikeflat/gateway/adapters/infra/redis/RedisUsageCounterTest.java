package com.mikeflat.gateway.adapters.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageOutcome;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisUsageCounterTest {

    private static final long PERIOD = 30_000_000L;
    private static final long TWO_PERIODS_SECONDS = 120;
    private static final RedisScript<Long> RECORD_USAGE =
            RedisScript.of(new ClassPathResource(RedisUsageCounter.RECORD_USAGE_SCRIPT), Long.class);

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisUsageCounter counter;

    @BeforeAll
    static void connect() {
        connectionFactory = connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(connectionFactory);
        counter = new RedisUsageCounter(redis, RECORD_USAGE, TWO_PERIODS_SECONDS);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void emptyDatabase() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void allowed_events_increment_used() {
        counter.record(allowed(1, PERIOD));
        counter.record(allowed(1, PERIOD));
        counter.record(allowed(1, PERIOD));

        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("3");
        assertThat(redis.hasKey("quota:rejected:1:" + PERIOD)).isFalse();
    }

    @Test
    void allowed_event_returns_new_used() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "9");

        assertThat(counter.record(allowed(1, PERIOD))).isEqualTo(10);
    }

    @Test
    void rejected_event_increments_rejected_and_returns_used() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "5");

        long used = counter.record(rejected(1, PERIOD));

        assertThat(used).isEqualTo(5);
        assertThat(redis.opsForValue().get("quota:rejected:1:" + PERIOD)).isEqualTo("1");
        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("5");
    }

    @Test
    void rejected_event_without_usage_returns_zero_and_creates_no_used_key() {
        long used = counter.record(rejected(2, PERIOD));

        assertThat(used).isZero();
        assertThat(redis.hasKey("quota:used:2:" + PERIOD)).isFalse();
        assertThat(redis.opsForValue().get("quota:rejected:2:" + PERIOD)).isEqualTo("1");
    }

    @Test
    void tenants_are_counted_separately() {
        counter.record(allowed(1, PERIOD));
        counter.record(allowed(1, PERIOD));
        counter.record(allowed(2, PERIOD));

        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("2");
        assertThat(redis.opsForValue().get("quota:used:2:" + PERIOD)).isEqualTo("1");
    }

    @Test
    void event_counts_toward_its_own_period() {
        redis.opsForValue().set("quota:used:1:" + (PERIOD + 1), "4");

        counter.record(allowed(1, PERIOD));

        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("1");
        assertThat(redis.opsForValue().get("quota:used:1:" + (PERIOD + 1))).isEqualTo("4");
    }

    @Test
    void new_period_starts_from_zero() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "7");

        assertThat(counter.record(allowed(1, PERIOD + 1))).isEqualTo(1);
    }

    @Test
    void new_key_gets_ttl_of_two_periods() {
        counter.record(allowed(1, PERIOD));
        counter.record(rejected(1, PERIOD));

        assertThat(redis.getExpire("quota:used:1:" + PERIOD, TimeUnit.SECONDS))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(TWO_PERIODS_SECONDS);
        assertThat(redis.getExpire("quota:rejected:1:" + PERIOD, TimeUnit.SECONDS))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(TWO_PERIODS_SECONDS);
    }

    @Test
    void later_increment_keeps_original_ttl() {
        String usedKey = "quota:used:1:" + PERIOD;
        counter.record(allowed(1, PERIOD));
        redis.expire(usedKey, Duration.ofSeconds(30));

        counter.record(allowed(1, PERIOD));

        assertThat(redis.getExpire(usedKey, TimeUnit.SECONDS)).isGreaterThan(0).isLessThanOrEqualTo(30);
    }

    @Test
    void event_is_counted_after_script_flush() {
        counter.record(allowed(1, PERIOD));
        redis.execute((RedisCallback<Void>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });

        assertThat(counter.record(allowed(1, PERIOD))).isEqualTo(2);
    }

    @Test
    void unreachable_redis_raises_recording_failure() {
        LettuceConnectionFactory closedPortFactory = connectionFactory("localhost", closedPort());
        try {
            RedisUsageCounter unreachable =
                    new RedisUsageCounter(new StringRedisTemplate(closedPortFactory), RECORD_USAGE, TWO_PERIODS_SECONDS);

            assertThatThrownBy(() -> unreachable.record(allowed(1, PERIOD)))
                    .isInstanceOf(UsageRecordingFailedException.class)
                    .hasMessageContaining("tenant 1")
                    .hasCauseInstanceOf(DataAccessException.class);
        } finally {
            closedPortFactory.destroy();
        }
    }

    @Test
    void ttl_below_one_second_is_rejected() {
        assertThatThrownBy(() -> new RedisUsageCounter(redis, RECORD_USAGE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    private static UsageEvent allowed(long tenantId, long periodIndex) {
        return new UsageEvent(tenantId, UsageOutcome.ALLOWED, periodIndex, periodIndex * 60);
    }

    private static UsageEvent rejected(long tenantId, long periodIndex) {
        return new UsageEvent(tenantId, UsageOutcome.REJECTED, periodIndex, periodIndex * 60);
    }

    private static LettuceConnectionFactory connectionFactory(String host, int port) {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                        .build())
                .build();
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfiguration);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
