package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "quota.limits.bronze=5")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class RedisStoppedMidRunTest {

    private static final long PERIOD_SECONDS = 60;
    private static final long FIRST_PERIOD = 30_000_000L;
    private static final long PERIODS_PER_TEST = 10;
    private static final long SECONDS_INTO_PERIOD = 30;
    private static final int BRONZE_LIMIT = 5;
    private static final int USED_BEFORE_OUTAGE = 3;
    private static final int REQUESTS_DURING_OUTAGE = 10;
    private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration HEALTH_DEADLINE = Duration.ofSeconds(2);
    private static final Duration PROBE_DEADLINE = Duration.ofSeconds(1);
    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(30);
    private static final String OUTAGE_WARNING = "Redis unavailable, usage recording failing:"
            + " no new tenants are blocked and counting is paused";
    private static final String RESUME_LINE = "Redis available again, usage recording resumed";
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
    private UsageRecorder usageRecorder;

    private long period;
    private boolean redisPaused;
    private int outputBeforeOutage;

    @BeforeEach
    void blockTenantOneAndBringTenantTwoToThreeThenPauseRedis(CapturedOutput output) throws Exception {
        period = FIRST_PERIOD + PERIODS_PER_TEST * testsStarted.getAndIncrement();
        clock.set(Instant.ofEpochSecond(period * PERIOD_SECONDS + SECONDS_INTO_PERIOD));
        sendRecordedRequests("1", BRONZE_LIMIT);
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(blockedTenants.isBlocked(1, period)).isTrue());
        sendRecordedRequests("2", USED_BEFORE_OUTAGE);
        assertThat(blockedTenants.isBlocked(2, period)).isFalse();
        outputBeforeOutage = output.getOut().length();
        pauseRedis();
    }

    @AfterEach
    void unpauseRedis() {
        if (redisPaused) {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            redisPaused = false;
        }
    }

    @Test
    void outage_keeps_decisions_and_reports_redis_down(CapturedOutput output) throws Exception {
        long failedBefore = usageRecorder.stats().failed();

        HttpResponse<String> blocked = timedGetAs("1", REDIS_TIMEOUT);
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).hasValue("30");
        for (int i = 0; i < REQUESTS_DURING_OUTAGE; i++) {
            assertThat(timedGetAs("2", REDIS_TIMEOUT).statusCode()).isEqualTo(200);
        }

        assertHealthDownWithProbesUp();
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(usageRecorder.stats().failed())
                .isGreaterThanOrEqualTo(failedBefore + 1 + REQUESTS_DURING_OUTAGE));
        assertThat(blockedTenants.isBlocked(2, period)).isFalse();
        String outageLog = logSinceOutage(output);
        assertThat(occurrences(outageLog, OUTAGE_WARNING)).isEqualTo(1);
        assertThat(outageLog).doesNotContain("lost").doesNotContain("dropped").doesNotContain(RESUME_LINE);
    }

    @Test
    void recovery_resumes_counting_and_blocking(CapturedOutput output) throws Exception {
        long failedBefore = usageRecorder.stats().failed();
        for (int i = 0; i < REQUESTS_DURING_OUTAGE; i++) {
            assertThat(getAs("2").statusCode()).isEqualTo(200);
        }
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(usageRecorder.stats().failed())
                .isGreaterThanOrEqualTo(failedBefore + REQUESTS_DURING_OUTAGE));

        unpauseRedis();

        Eventually.within(RECORDING_DEADLINE, () -> {
            HttpResponse<String> health = HttpProbe.get(port, "/actuator/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(health.body()).path("status").asString()).isEqualTo("UP");
        });
        assertThat(Long.parseLong(redis.opsForValue().get(usedKey(2)))).isGreaterThanOrEqualTo(USED_BEFORE_OUTAGE);
        assertThat(blockedTenants.isBlocked(2, period)).isFalse();
        sendRecordedRequestsUntilBlocked("2");
        assertThat(Long.parseLong(redis.opsForValue().get(usedKey(2)))).isGreaterThanOrEqualTo(BRONZE_LIMIT);
        assertThat(getAs("2").statusCode()).isEqualTo(429);
        String outageLog = logSinceOutage(output);
        assertThat(occurrences(outageLog, OUTAGE_WARNING)).isEqualTo(1);
        assertThat(occurrences(outageLog, RESUME_LINE)).isEqualTo(1);
        assertThat(outageLog).doesNotContain("lost").doesNotContain("dropped");
    }

    @Test
    void period_rollover_during_outage_unblocks_without_new_blocks() throws Exception {
        long nextPeriod = period + 1;
        long failedBefore = usageRecorder.stats().failed();
        clock.set(Instant.ofEpochSecond(nextPeriod * PERIOD_SECONDS + SECONDS_INTO_PERIOD));

        for (int i = 0; i < BRONZE_LIMIT + 1; i++) {
            assertThat(timedGetAs("1", REDIS_TIMEOUT).statusCode()).isEqualTo(200);
        }

        Eventually.within(RECORDING_DEADLINE, () -> assertThat(usageRecorder.stats().failed())
                .isGreaterThanOrEqualTo(failedBefore + BRONZE_LIMIT + 1));
        assertThat(blockedTenants.isBlocked(1, nextPeriod)).isFalse();
        assertThat(blockedTenants.isBlocked(2, nextPeriod)).isFalse();
        assertThat(timedGetAs("1", REDIS_TIMEOUT).statusCode()).isEqualTo(200);
    }

    private void assertHealthDownWithProbesUp() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> health = HttpProbe.get(port, "/actuator/health");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(HEALTH_DEADLINE);
        assertThat(health.statusCode()).isEqualTo(503);
        JsonNode body = JSON.readTree(health.body());
        assertThat(body.path("status").asString()).isEqualTo("DOWN");
        assertThat(body.path("components").path("redis").path("status").asString()).isEqualTo("DOWN");

        for (String probe : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            long probeStarted = System.nanoTime();
            HttpResponse<String> response = HttpProbe.get(port, probe);
            assertThat(Duration.ofNanos(System.nanoTime() - probeStarted)).isLessThan(PROBE_DEADLINE);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("UP");
        }
    }

    private void sendRecordedRequests(String tenantId, int count) throws Exception {
        for (int i = 1; i <= count; i++) {
            assertThat(getAs(tenantId).statusCode()).isEqualTo(200);
            String expectedUsed = Integer.toString(i);
            Eventually.within(RECORDING_DEADLINE,
                    () -> assertThat(redis.opsForValue().get(usedKey(Long.parseLong(tenantId))))
                            .isEqualTo(expectedUsed));
        }
    }

    private void sendRecordedRequestsUntilBlocked(String tenantId) throws Exception {
        long tenant = Long.parseLong(tenantId);
        for (int i = 0; i < BRONZE_LIMIT && !blockedTenants.isBlocked(tenant, period); i++) {
            long recordedBefore = usageRecorder.stats().recorded();
            int status = getAs(tenantId).statusCode();
            if (status == 429) {
                break;
            }
            assertThat(status).isEqualTo(200);
            Eventually.within(RECORDING_DEADLINE,
                    () -> assertThat(usageRecorder.stats().recorded()).isGreaterThan(recordedBefore));
        }
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(blockedTenants.isBlocked(tenant, period)).isTrue());
    }

    private void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        redisPaused = true;
    }

    private String logSinceOutage(CapturedOutput output) {
        return output.getOut().substring(outputBeforeOutage);
    }

    private static int occurrences(String text, String line) {
        return text.split(java.util.regex.Pattern.quote(line), -1).length - 1;
    }

    private HttpResponse<String> timedGetAs(String tenantId, Duration deadline) throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = getAs(tenantId);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(deadline);
        return response;
    }

    private HttpResponse<String> getAs(String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }

    private String usedKey(long tenantId) {
        return "quota:used:" + tenantId + ":" + period;
    }
}
