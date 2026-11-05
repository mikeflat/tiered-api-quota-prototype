package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "quota.limits.bronze=5")
@Testcontainers
class BlockedTenantWhileRedisDownTest {

    private static final long PERIOD_SECONDS = 60;
    private static final long FIRST_PERIOD = 30_000_000L;
    private static final long SECONDS_INTO_PERIOD = 30;
    private static final int BRONZE_LIMIT = 5;
    private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(15);
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
    void blockBronzeTenantThenStopRedis() throws Exception {
        period = FIRST_PERIOD + testsStarted.getAndIncrement();
        clock.set(Instant.ofEpochSecond(period * PERIOD_SECONDS + SECONDS_INTO_PERIOD));
        for (int i = 1; i <= BRONZE_LIMIT; i++) {
            assertThat(getAs("1").statusCode()).isEqualTo(200);
            String expectedUsed = Integer.toString(i);
            Eventually.within(RECORDING_DEADLINE,
                    () -> assertThat(redis.opsForValue().get("quota:used:1:" + period)).isEqualTo(expectedUsed));
        }
        Eventually.within(RECORDING_DEADLINE, () -> assertThat(blockedTenants.isBlocked(1, period)).isTrue());
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    @AfterEach
    void startRedisAgain() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    @Test
    void blocked_tenant_stays_blocked_while_redis_is_unreachable() throws Exception {
        for (int i = 0; i < 3; i++) {
            long started = System.nanoTime();

            HttpResponse<String> response = getAs("1");

            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.headers().firstValue("Retry-After")).hasValue("30");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(REDIS_TIMEOUT);
        }
    }

    @Test
    void unblocked_tenant_is_served_while_redis_is_unreachable() throws Exception {
        for (int i = 0; i < 3; i++) {
            long started = System.nanoTime();

            HttpResponse<String> response = getAs("2");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("mock-resource");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(REDIS_TIMEOUT);
        }
    }

    private HttpResponse<String> getAs(String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }
}
