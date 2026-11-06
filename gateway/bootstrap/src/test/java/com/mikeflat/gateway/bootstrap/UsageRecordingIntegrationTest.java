package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Import(ObservedUsageCounterConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class UsageRecordingIntegrationTest {

    private static final Instant ARRIVAL = Instant.ofEpochSecond(1_800_000_030L);
    private static final long PERIOD = 30_000_000L;
    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(10);

    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedArrivalClock {

        @Bean
        @Primary
        Clock fixedArrivalClock() {
            return Clock.fixed(ARRIVAL, ZoneOffset.UTC);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObservedUsageCounter usageCounter;

    @BeforeEach
    void emptyDatabase() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void allowed_requests_are_counted_per_tenant_and_period() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(getAs("1").statusCode()).isEqualTo(200);
        }
        assertThat(getAs("14").statusCode()).isEqualTo(200);

        Eventually.within(RECORDING_DEADLINE, () -> {
            assertThat(redis.opsForValue().get(usedKey(1))).isEqualTo("3");
            assertThat(redis.opsForValue().get(usedKey(14))).isEqualTo("1");
        });
        assertThat(redis.hasKey("quota:rejected:1:" + PERIOD)).isFalse();
    }

    @Test
    void guard_rejections_are_not_counted() throws Exception {
        long recordedBefore = usageCounter.recorded();

        assertThat(getAs("999").statusCode()).isEqualTo(400);
        assertThat(HttpProbe.get(port, "/api/v1/resource").statusCode()).isEqualTo(400);
        assertThat(getAs("2").statusCode()).isEqualTo(200);

        Eventually.within(RECORDING_DEADLINE,
                () -> assertThat(redis.opsForValue().get(usedKey(2))).isEqualTo("1"));
        assertThat(usageCounter.recorded()).isEqualTo(recordedBefore + 1);
        assertThat(redis.keys("quota:*")).containsExactly(usedKey(2));
    }

    @Test
    void counting_resumes_after_redis_returns() throws Exception {
        long failedBefore = usageCounter.failed();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(getAs("3").statusCode()).isEqualTo(200);
            Eventually.within(RECORDING_DEADLINE,
                    () -> assertThat(usageCounter.failed()).isGreaterThan(failedBefore));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        assertThat(getAs("4").statusCode()).isEqualTo(200);

        Eventually.within(RECORDING_DEADLINE,
                () -> assertThat(redis.opsForValue().get(usedKey(4))).isEqualTo("1"));
    }

    private HttpResponse<String> getAs(String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }

    private static String usedKey(long tenantId) {
        return "quota:used:" + tenantId + ":" + PERIOD;
    }
}
