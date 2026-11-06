package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"quota.limits.bronze=5", "quota.limits.silver=50"})
@Testcontainers
class TierChangeIntegrationTest {

    private static final long PERIOD_SECONDS = 60;
    private static final long FIRST_PERIOD = 30_000_000L;
    private static final long PERIODS_PER_TEST = 10;
    private static final long SECONDS_INTO_PERIOD = 30;
    private static final int BRONZE_LIMIT = 5;
    private static final int SILVER_LIMIT = 50;
    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(10);
    private static final AtomicLong testsStarted = new AtomicLong();

    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    @TestConfiguration(proxyBeanMethods = false)
    static class AdjustableClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.ofEpochSecond(FIRST_PERIOD * PERIOD_SECONDS));
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MutableClock clock;

    @Autowired
    private BlockedTenants blockedTenants;

    @Autowired
    private TenantRegistry registry;

    private long period;

    @BeforeEach
    void emptyDatabaseAndFreshPeriod() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        period = FIRST_PERIOD + testsStarted.getAndIncrement() * PERIODS_PER_TEST;
        clock.set(Instant.ofEpochSecond(period * PERIOD_SECONDS + SECONDS_INTO_PERIOD));
    }

    @AfterEach
    void restoreSeedTiers() {
        registry.changeTier(1, Tier.BRONZE);
        registry.changeTier(9, Tier.SILVER);
    }

    @Test
    void blocked_bronze_tenant_upgraded_is_served_and_not_blocked_again() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);
        awaitBlocked(1);
        assertThat(getAs("1").statusCode()).isEqualTo(429);

        HttpResponse<String> change = putTier("1", "{\"tier\":\"silver\"}");

        assertThat(change.statusCode()).isEqualTo(200);
        assertThat(change.body()).isEqualTo("{\"id\":1,\"name\":\"Acme Corp\",\"tier\":\"Silver\",\"limit\":50}");
        assertThat(getAs("1").statusCode()).isEqualTo(200);
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(redis.opsForValue().get(usedKey(1))).isEqualTo("6"));
        assertThat(blockedTenants.isBlocked(1, period)).isFalse();
        assertThat(getAs("1").statusCode()).isEqualTo(200);
    }

    @Test
    void silver_tenant_over_bronze_limit_downgraded_is_served_then_blocked_again() throws Exception {
        redis.opsForValue().set(usedKey(9), Integer.toString(SILVER_LIMIT));
        blockedTenants.block(9, period);
        assertThat(getAs("9").statusCode()).isEqualTo(429);

        assertThat(putTier("9", "{\"tier\":\"Bronze\"}").statusCode()).isEqualTo(200);

        assertThat(getAs("9").statusCode()).isEqualTo(200);
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(redis.opsForValue().get(usedKey(9))).isEqualTo("51"));
        awaitBlocked(9);
        assertThat(getAs("9").statusCode()).isEqualTo(429);
    }

    @Test
    void other_blocked_tenants_stay_blocked() throws Exception {
        blockedTenants.block(1, period);
        blockedTenants.block(2, period);

        assertThat(putTier("1", "{\"tier\":\"Gold\"}").statusCode()).isEqualTo(200);

        assertThat(getAs("1").statusCode()).isEqualTo(200);
        assertThat(getAs("2").statusCode()).isEqualTo(429);
    }

    @Test
    void counters_are_untouched_by_the_change() throws Exception {
        redis.opsForValue().set(usedKey(1), "40");
        redis.opsForValue().set("quota:rejected:1:" + period, "3");

        assertThat(putTier("1", "{\"tier\":\"Gold\"}").statusCode()).isEqualTo(200);

        assertThat(redis.opsForValue().get(usedKey(1))).isEqualTo("40");
        assertThat(redis.opsForValue().get("quota:rejected:1:" + period)).isEqualTo("3");
        assertThat(HttpProbe.get(port, "/admin/snapshot?id=1").body())
                .contains("\"tier\":\"Gold\"", "\"limit\":10000", "\"used\":40", "\"status\":\"ok\"");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "99   | {\"tier\":\"Gold\"}     | 404",
        "1    | {\"tier\":\"Platinum\"} | 400",
        "1    | {}                      | 400",
        "1    | {\"tier\":              | 400",
        "acme | {\"tier\":\"Gold\"}     | 400",
        "0    | {\"tier\":\"Gold\"}     | 400",
    })
    void invalid_tier_change_is_a_problem_and_changes_nothing(String id, String body, int status) throws Exception {
        HttpResponse<String> response = putTier(id, body);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(registry.find(1).orElseThrow().tier()).isEqualTo(Tier.BRONZE);
    }

    private HttpResponse<String> putTier(String id, String body) throws Exception {
        return HttpProbe.putJson(port, "/admin/tenants/" + id + "/tier", body);
    }

    private void sendRecordedRequests(String tenantId, int count) throws Exception {
        for (int i = 1; i <= count; i++) {
            assertThat(getAs(tenantId).statusCode()).isEqualTo(200);
            String expectedUsed = Integer.toString(i);
            Eventually.within(RECORDING_DEADLINE,
                    () -> assertThat(redis.opsForValue().get(usedKey(Long.parseLong(tenantId)))).isEqualTo(expectedUsed));
        }
    }

    private void awaitBlocked(long tenantId) throws Exception {
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(blockedTenants.isBlocked(tenantId, period)).isTrue());
    }

    private HttpResponse<String> getAs(String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }

    private String usedKey(long tenantId) {
        return "quota:used:" + tenantId + ":" + period;
    }
}
