package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
class TrafficMetricsRecordingIntegrationTest {

    private static final long PERIOD = 30_000_000L;
    private static final long SECOND = PERIOD * 60 + 30;
    private static final int BRONZE_LIMIT = 5;
    private static final int SILVER_TENANT_REQUESTS = 3;
    private static final int MAX_BRONZE_REQUESTS = 200;
    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(10);
    private static final String BRONZE_TENANT = "1";
    private static final String SILVER_TENANT = "9";

    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.ofEpochSecond(SECOND), ZoneOffset.UTC);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private BlockedTenants blockedTenants;

    @Test
    void recorded_traffic_fills_global_counts_and_status_sets() throws Exception {
        int silverServed = 0;
        for (int i = 0; i < SILVER_TENANT_REQUESTS; i++) {
            assertThat(getAs(SILVER_TENANT).statusCode()).isEqualTo(200);
            silverServed++;
        }
        int bronzeServed = 0;
        int bronzeRejected = 0;
        for (int i = 0; i < MAX_BRONZE_REQUESTS && bronzeRejected == 0; i++) {
            int status = getAs(BRONZE_TENANT).statusCode();
            if (status == 200) {
                bronzeServed++;
            } else {
                assertThat(status).isEqualTo(429);
                bronzeRejected++;
            }
        }
        long allowed = bronzeServed + silverServed;
        long rejected = bronzeRejected;

        assertThat(bronzeRejected).isEqualTo(1);
        assertThat(bronzeServed).isGreaterThanOrEqualTo(BRONZE_LIMIT);
        Eventually.within(RECORDING_DEADLINE, () -> {
            assertThat(counterValue("traffic:allowed:" + SECOND)).isEqualTo(allowed);
            assertThat(counterValue("traffic:rejected:" + SECOND)).isEqualTo(rejected);
        });
        assertThat(counterValue("traffic:total:allowed:" + PERIOD)).isEqualTo(allowed);
        assertThat(counterValue("traffic:total:rejected:" + PERIOD)).isEqualTo(rejected);
        assertThat(redis.opsForSet().members("quota:over:" + PERIOD)).containsExactly(BRONZE_TENANT);
        assertThat(redis.opsForSet().isMember("quota:near:" + PERIOD, BRONZE_TENANT)).isFalse();
        assertThat(redis.opsForSet().isMember("quota:near:" + PERIOD, SILVER_TENANT)).isFalse();
        assertThat(blockedTenants.isBlocked(1, PERIOD)).isTrue();
        assertThat(blockedTenants.isBlocked(9, PERIOD)).isFalse();
    }

    private long counterValue(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private HttpResponse<String> getAs(String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }
}
