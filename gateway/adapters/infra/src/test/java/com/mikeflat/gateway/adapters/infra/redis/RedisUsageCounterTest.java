package com.mikeflat.gateway.adapters.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import com.mikeflat.gateway.domain.quota.UsageEvent;
import com.mikeflat.gateway.domain.quota.UsageLimits;
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
    private static final long SECOND = PERIOD * 60;
    private static final long TWO_PERIODS_SECONDS = 120;
    private static final UsageLimits ROOMY = new UsageLimits(100, 80);
    private static final UsageLimits BRONZE_LIMIT_TEN = new UsageLimits(10, 8);
    private static final String TENANT_1 = "1";
    private static final String TENANT_2 = "2";
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
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(1, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("3");
        assertThat(redis.hasKey("quota:rejected:1:" + PERIOD)).isFalse();
    }

    @Test
    void allowed_event_returns_new_used() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "9");

        assertThat(counter.record(allowed(1, PERIOD), ROOMY)).isEqualTo(10);
    }

    @Test
    void rejected_event_increments_rejected_and_returns_used() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "5");

        long used = counter.record(rejected(1, PERIOD), ROOMY);

        assertThat(used).isEqualTo(5);
        assertThat(redis.opsForValue().get("quota:rejected:1:" + PERIOD)).isEqualTo("1");
        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("5");
    }

    @Test
    void rejected_event_without_usage_returns_zero_and_creates_no_used_key() {
        long used = counter.record(rejected(2, PERIOD), ROOMY);

        assertThat(used).isZero();
        assertThat(redis.hasKey("quota:used:2:" + PERIOD)).isFalse();
        assertThat(redis.opsForValue().get("quota:rejected:2:" + PERIOD)).isEqualTo("1");
    }

    @Test
    void tenants_are_counted_separately() {
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(2, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("2");
        assertThat(redis.opsForValue().get("quota:used:2:" + PERIOD)).isEqualTo("1");
    }

    @Test
    void event_counts_toward_its_own_period() {
        redis.opsForValue().set("quota:used:1:" + (PERIOD + 1), "4");

        counter.record(allowed(1, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("1");
        assertThat(redis.opsForValue().get("quota:used:1:" + (PERIOD + 1))).isEqualTo("4");
    }

    @Test
    void new_period_starts_from_zero() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "7");

        assertThat(counter.record(allowed(1, PERIOD + 1), ROOMY)).isEqualTo(1);
    }

    @Test
    void new_key_gets_ttl_of_two_periods() {
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(rejected(1, PERIOD), ROOMY);

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
        counter.record(allowed(1, PERIOD), ROOMY);
        redis.expire(usedKey, Duration.ofSeconds(30));

        counter.record(allowed(1, PERIOD), ROOMY);

        assertThat(redis.getExpire(usedKey, TimeUnit.SECONDS)).isGreaterThan(0).isLessThanOrEqualTo(30);
    }

    @Test
    void event_is_counted_after_script_flush() {
        counter.record(allowed(1, PERIOD), ROOMY);
        redis.execute((RedisCallback<Void>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });

        assertThat(counter.record(allowed(1, PERIOD), ROOMY)).isEqualTo(2);
    }

    @Test
    void unreachable_redis_raises_recording_failure() {
        LettuceConnectionFactory closedPortFactory = connectionFactory("localhost", closedPort());
        try {
            RedisUsageCounter unreachable =
                    new RedisUsageCounter(new StringRedisTemplate(closedPortFactory), RECORD_USAGE, TWO_PERIODS_SECONDS);

            assertThatThrownBy(() -> unreachable.record(allowed(1, PERIOD), ROOMY))
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

    @Test
    void allowed_events_of_several_tenants_add_up_per_second() {
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(2, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("traffic:allowed:" + SECOND)).isEqualTo("3");
        assertThat(redis.hasKey("traffic:rejected:" + SECOND)).isFalse();
    }

    @Test
    void rejected_event_increments_rejected_second_and_total() {
        counter.record(allowed(1, PERIOD), ROOMY);

        counter.record(rejected(1, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("traffic:rejected:" + SECOND)).isEqualTo("1");
        assertThat(redis.opsForValue().get("traffic:total:rejected:" + PERIOD)).isEqualTo("1");
        assertThat(redis.opsForValue().get("traffic:allowed:" + SECOND)).isEqualTo("1");
        assertThat(redis.opsForValue().get("traffic:total:allowed:" + PERIOD)).isEqualTo("1");
    }

    @Test
    void late_event_counts_toward_its_own_second_and_period() {
        redis.opsForValue().set("traffic:allowed:" + (SECOND + 60), "4");
        redis.opsForValue().set("traffic:total:allowed:" + (PERIOD + 1), "4");

        counter.record(allowed(1, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("traffic:allowed:" + SECOND)).isEqualTo("1");
        assertThat(redis.hasKey("traffic:allowed:" + (SECOND + 1))).isFalse();
        assertThat(redis.opsForValue().get("traffic:allowed:" + (SECOND + 60))).isEqualTo("4");
        assertThat(redis.opsForValue().get("traffic:total:allowed:" + PERIOD)).isEqualTo("1");
        assertThat(redis.opsForValue().get("traffic:total:allowed:" + (PERIOD + 1))).isEqualTo("4");
    }

    @Test
    void period_totals_add_up_across_tenants() {
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(allowed(1, PERIOD), ROOMY);
        counter.record(rejected(1, PERIOD), ROOMY);
        counter.record(allowed(2, PERIOD), ROOMY);
        counter.record(allowed(2, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("traffic:total:allowed:" + PERIOD)).isEqualTo("5");
        assertThat(redis.opsForValue().get("traffic:total:rejected:" + PERIOD)).isEqualTo("1");
    }

    @Test
    void new_period_total_starts_from_zero() {
        redis.opsForValue().set("traffic:total:allowed:" + PERIOD, "40");

        counter.record(allowed(1, PERIOD + 1), ROOMY);

        assertThat(redis.opsForValue().get("traffic:total:allowed:" + (PERIOD + 1))).isEqualTo("1");
    }

    @Test
    void second_key_ttl_is_120_seconds_regardless_of_period() {
        long twoTenMinutePeriods = 1_200;
        RedisUsageCounter tenMinutePeriods = new RedisUsageCounter(redis, RECORD_USAGE, twoTenMinutePeriods);

        tenMinutePeriods.record(rejected(1, PERIOD), ROOMY);

        assertThat(redis.getExpire("traffic:rejected:" + SECOND, TimeUnit.SECONDS))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(RedisUsageCounter.SECOND_KEY_TTL_SECONDS);
        assertThat(redis.getExpire("traffic:total:rejected:" + PERIOD, TimeUnit.SECONDS))
                .isGreaterThan(RedisUsageCounter.SECOND_KEY_TTL_SECONDS)
                .isLessThanOrEqualTo(twoTenMinutePeriods);
    }

    @Test
    void later_second_increment_keeps_original_ttl() {
        String secondKey = "traffic:allowed:" + SECOND;
        counter.record(allowed(1, PERIOD), ROOMY);
        redis.expire(secondKey, Duration.ofSeconds(30));

        counter.record(allowed(1, PERIOD), ROOMY);

        assertThat(redis.getExpire(secondKey, TimeUnit.SECONDS)).isGreaterThan(0).isLessThanOrEqualTo(30);
    }

    @Test
    void period_total_and_set_ttl_is_two_periods() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "9");

        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(redis.getExpire("traffic:total:allowed:" + PERIOD, TimeUnit.SECONDS))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(TWO_PERIODS_SECONDS);
        assertThat(redis.getExpire("quota:over:" + PERIOD, TimeUnit.SECONDS))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(TWO_PERIODS_SECONDS);
    }

    @Test
    void later_set_member_keeps_original_ttl() {
        String nearKey = "quota:near:" + PERIOD;
        redis.opsForValue().set("quota:used:1:" + PERIOD, "7");
        redis.opsForValue().set("quota:used:2:" + PERIOD, "7");
        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);
        redis.expire(nearKey, Duration.ofSeconds(30));

        counter.record(allowed(2, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(redis.opsForSet().members(nearKey)).containsExactlyInAnyOrder(TENANT_1, TENANT_2);
        assertThat(redis.getExpire(nearKey, TimeUnit.SECONDS)).isGreaterThan(0).isLessThanOrEqualTo(30);
    }

    @Test
    void recreated_set_gets_ttl() {
        String nearKey = "quota:near:" + PERIOD;
        redis.opsForValue().set("quota:used:1:" + PERIOD, "8");
        redis.opsForValue().set("quota:used:2:" + PERIOD, "7");
        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);
        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);
        assertThat(redis.hasKey(nearKey)).isFalse();

        counter.record(allowed(2, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(redis.opsForSet().members(nearKey)).containsExactly(TENANT_2);
        assertThat(redis.getExpire(nearKey, TimeUnit.SECONDS))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(TWO_PERIODS_SECONDS);
    }

    @Test
    void used_at_near_boundary_joins_near() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "7");

        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(redis.opsForSet().members("quota:near:" + PERIOD)).containsExactly(TENANT_1);
        assertThat(redis.hasKey("quota:over:" + PERIOD)).isFalse();
    }

    @Test
    void used_below_near_boundary_joins_neither() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "6");

        assertThat(counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN)).isEqualTo(7);

        assertThat(redis.hasKey("quota:near:" + PERIOD)).isFalse();
        assertThat(redis.hasKey("quota:over:" + PERIOD)).isFalse();
    }

    @Test
    void reaching_limit_moves_from_near_to_over() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "8");
        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);
        assertThat(redis.opsForSet().members("quota:near:" + PERIOD)).containsExactly(TENANT_1);

        assertThat(counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN)).isEqualTo(10);

        assertThat(redis.opsForSet().members("quota:over:" + PERIOD)).containsExactly(TENANT_1);
        assertThat(redis.hasKey("quota:near:" + PERIOD)).isFalse();
    }

    @Test
    void overshoot_stays_over_only() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "9");

        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);
        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);
        long used = counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(used).isEqualTo(12);
        assertThat(redis.opsForSet().members("quota:over:" + PERIOD)).containsExactly(TENANT_1);
        assertThat(redis.hasKey("quota:near:" + PERIOD)).isFalse();
    }

    @Test
    void rejected_event_keeps_over_status() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "9");
        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);

        long used = counter.record(rejected(1, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(used).isEqualTo(10);
        assertThat(redis.opsForValue().get("quota:used:1:" + PERIOD)).isEqualTo("10");
        assertThat(redis.opsForSet().members("quota:over:" + PERIOD)).containsExactly(TENANT_1);
        assertThat(redis.hasKey("quota:near:" + PERIOD)).isFalse();
    }

    @Test
    void rejected_event_without_usage_joins_neither() {
        counter.record(rejected(2, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(redis.hasKey("quota:used:2:" + PERIOD)).isFalse();
        assertThat(redis.hasKey("quota:near:" + PERIOD)).isFalse();
        assertThat(redis.hasKey("quota:over:" + PERIOD)).isFalse();
    }

    @Test
    void late_event_updates_sets_of_its_own_period() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "9");

        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(redis.opsForSet().members("quota:over:" + PERIOD)).containsExactly(TENANT_1);
        assertThat(redis.hasKey("quota:over:" + (PERIOD + 1))).isFalse();
    }

    @Test
    void status_follows_limits_passed_in() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "10");
        redis.opsForSet().add("quota:over:" + PERIOD, TENANT_1);

        long used = counter.record(allowed(1, PERIOD), ROOMY);

        assertThat(used).isEqualTo(11);
        assertThat(redis.hasKey("quota:over:" + PERIOD)).isFalse();
        assertThat(redis.hasKey("quota:near:" + PERIOD)).isFalse();
    }

    @Test
    void allowed_event_below_near_writes_only_three_keys() {
        counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(redis.keys("*")).containsExactlyInAnyOrder(
                "quota:used:1:" + PERIOD, "traffic:allowed:" + SECOND, "traffic:total:allowed:" + PERIOD);
    }

    @Test
    void counters_and_metrics_change_together() {
        redis.opsForValue().set("quota:used:1:" + PERIOD, "7");

        long used = counter.record(allowed(1, PERIOD), BRONZE_LIMIT_TEN);

        assertThat(used).isEqualTo(8);
        assertThat(redis.opsForValue().get("traffic:allowed:" + SECOND)).isEqualTo("1");
        assertThat(redis.opsForValue().get("traffic:total:allowed:" + PERIOD)).isEqualTo("1");
        assertThat(redis.opsForSet().members("quota:near:" + PERIOD)).containsExactly(TENANT_1);
    }

    @Test
    void script_cache_flush_still_records_metrics() {
        counter.record(allowed(1, PERIOD), ROOMY);
        redis.execute((RedisCallback<Void>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });

        counter.record(allowed(1, PERIOD), ROOMY);

        assertThat(redis.opsForValue().get("traffic:allowed:" + SECOND)).isEqualTo("2");
        assertThat(redis.opsForValue().get("traffic:total:allowed:" + PERIOD)).isEqualTo("2");
    }

    @Test
    void key_builders_match_documented_names() {
        assertThat(RedisUsageCounter.secondKey(UsageOutcome.ALLOWED, SECOND)).isEqualTo("traffic:allowed:" + SECOND);
        assertThat(RedisUsageCounter.secondKey(UsageOutcome.REJECTED, SECOND)).isEqualTo("traffic:rejected:" + SECOND);
        assertThat(RedisUsageCounter.periodTotalKey(UsageOutcome.ALLOWED, PERIOD))
                .isEqualTo("traffic:total:allowed:" + PERIOD);
        assertThat(RedisUsageCounter.periodTotalKey(UsageOutcome.REJECTED, PERIOD))
                .isEqualTo("traffic:total:rejected:" + PERIOD);
        assertThat(RedisUsageCounter.nearKey(PERIOD)).isEqualTo("quota:near:" + PERIOD);
        assertThat(RedisUsageCounter.overKey(PERIOD)).isEqualTo("quota:over:" + PERIOD);
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
