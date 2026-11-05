package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "quota.limits.bronze=5")
@Testcontainers
class QuotaEnforcementIntegrationTest {

    private static final long PERIOD_SECONDS = 60;
    private static final long FIRST_PERIOD = 30_000_000L;
    private static final long PERIODS_PER_TEST = 10;
    private static final long SECONDS_INTO_PERIOD = 30;
    private static final int BRONZE_LIMIT = 5;
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

    @Test
    void tenant_is_rejected_after_reaching_limit() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);

        awaitBlocked(1, period);

        HttpResponse<String> rejected = getAs("1");
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.body()).doesNotContain("mock-resource");
    }

    @Test
    void rejection_carries_retry_after_to_next_boundary() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);
        awaitBlocked(1, period);

        HttpResponse<String> middle = getAs("1");
        clock.set(Instant.ofEpochSecond(period * PERIOD_SECONDS + 59));
        HttpResponse<String> lastSecond = getAs("1");

        assertThat(middle.headers().firstValue("Retry-After")).hasValue("30");
        assertThat(lastSecond.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(MediaType.parseMediaType(middle.headers().firstValue("Content-Type").orElseThrow())
                .equalsTypeAndSubtype(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(middle.body())
                .contains("\"status\":429")
                .contains("\"title\":\"Too Many Requests\"")
                .contains("\"detail\":\"Quota for the current period is exhausted\"");
    }

    @Test
    void rejections_are_counted_as_rejected_not_used() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);
        awaitBlocked(1, period);

        for (int i = 0; i < 3; i++) {
            assertThat(getAs("1").statusCode()).isEqualTo(429);
        }

        Eventually.within(RECORDING_DEADLINE,
                () -> assertThat(redis.opsForValue().get(rejectedKey(1, period))).isEqualTo("3"));
        assertThat(redis.opsForValue().get(usedKey(1, period))).isEqualTo("5");
    }

    @Test
    void other_tenants_are_not_affected_by_a_block() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);
        awaitBlocked(1, period);

        assertThat(getAs("1").statusCode()).isEqualTo(429);
        assertThat(getAs("2").statusCode()).isEqualTo(200);
    }

    @Test
    void burst_counts_match_responses() throws Exception {
        List<Integer> statuses = new ArrayList<>(sendBurst("1", 20));
        awaitAllRecorded(1, statuses.size());
        statuses.addAll(sendBurst("1", 10));
        awaitAllRecorded(1, statuses.size());

        long served = statuses.stream().filter(status -> status == 200).count();
        long rejected = statuses.stream().filter(status -> status == 429).count();
        assertThat(served + rejected).isEqualTo(statuses.size());
        assertThat(served).isGreaterThanOrEqualTo(BRONZE_LIMIT);
        assertThat(rejected).isGreaterThanOrEqualTo(1);
        assertThat(counterValue(usedKey(1, period))).isEqualTo(served);
        assertThat(counterValue(rejectedKey(1, period))).isEqualTo(rejected);
    }

    @Test
    void blocked_tenant_is_served_again_in_next_period() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);
        awaitBlocked(1, period);

        clock.advance(Duration.ofSeconds(PERIOD_SECONDS));

        assertThat(getAs("1").statusCode()).isEqualTo(200);
        Eventually.within(RECORDING_DEADLINE,
                () -> assertThat(redis.opsForValue().get(usedKey(1, period + 1))).isEqualTo("1"));
    }

    @Test
    void tenant_is_blocked_again_in_next_period() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);
        awaitBlocked(1, period);
        clock.advance(Duration.ofSeconds(PERIOD_SECONDS));

        sendRecordedRequests("1", BRONZE_LIMIT, period + 1);

        awaitBlocked(1, period + 1);
        assertThat(getAs("1").statusCode()).isEqualTo(429);
    }

    private void sendRecordedRequests(String tenantId, int count) throws Exception {
        sendRecordedRequests(tenantId, count, period);
    }

    private void sendRecordedRequests(String tenantId, int count, long periodIndex) throws Exception {
        long tenant = Long.parseLong(tenantId);
        for (int i = 1; i <= count; i++) {
            assertThat(getAs(tenantId).statusCode()).isEqualTo(200);
            String expectedUsed = Integer.toString(i);
            Eventually.within(RECORDING_DEADLINE,
                    () -> assertThat(redis.opsForValue().get(usedKey(tenant, periodIndex))).isEqualTo(expectedUsed));
        }
    }

    private void awaitBlocked(long tenantId, long periodIndex) throws Exception {
        Eventually.within(RECORDING_DEADLINE,
                () -> assertThat(blockedTenants.isBlocked(tenantId, periodIndex)).isTrue());
    }

    private void awaitAllRecorded(long tenantId, int total) throws Exception {
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(
                counterValue(usedKey(tenantId, period)) + counterValue(rejectedKey(tenantId, period)))
                .isEqualTo(total));
    }

    private List<Integer> sendBurst(String tenantId, int requests) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                calls.add(() -> getAs(tenantId).statusCode());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> status : clients.invokeAll(calls)) {
                statuses.add(status.get());
            }
            return statuses;
        } finally {
            clients.shutdownNow();
        }
    }

    private long counterValue(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private HttpResponse<String> getAs(String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }

    private static String usedKey(long tenantId, long periodIndex) {
        return "quota:used:" + tenantId + ":" + periodIndex;
    }

    private static String rejectedKey(long tenantId, long periodIndex) {
        return "quota:rejected:" + tenantId + ":" + periodIndex;
    }
}
