package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AdminWithoutRedisTest {

    private static final long PERIOD_SECONDS = 60;
    private static final long PERIOD = 30_000_000L;
    private static final Duration ANSWER_DEADLINE = Duration.ofSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> CLOSED_PORT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.ofEpochSecond(PERIOD * PERIOD_SECONDS + 30));
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private BlockedTenants blockedTenants;

    @Autowired
    private TenantRegistry registry;

    @AfterEach
    void restoreSeedTier() {
        registry.changeTier(1, Tier.BRONZE);
    }

    @Test
    void tiers_are_listed() throws Exception {
        HttpResponse<String> response = timed(() -> HttpProbe.get(port, "/admin/tiers"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).size()).isEqualTo(3);
    }

    @Test
    void tier_change_unblocks_a_blocked_tenant() throws Exception {
        blockedTenants.block(1, PERIOD);
        assertThat(getAs("1").statusCode()).isEqualTo(429);

        HttpResponse<String> change =
                timed(() -> HttpProbe.putJson(port, "/admin/tenants/1/tier", "{\"tier\":\"Gold\"}"));

        assertThat(change.statusCode()).isEqualTo(200);
        assertThat(change.body()).contains("\"tier\":\"Gold\"", "\"limit\":10000");
        assertThat(getAs("1").statusCode()).isEqualTo(200);
    }

    @Test
    void snapshot_has_null_metrics_and_rows_without_usage() throws Exception {
        HttpResponse<String> response = timed(() -> HttpProbe.get(port, "/admin/snapshot?tier=Gold"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"metrics\":null");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("period").path("index").asLong()).isEqualTo(PERIOD);
        JsonNode tenants = body.path("tenants");
        assertThat(tenants.path("rowsAvailable").asBoolean()).isTrue();
        assertThat(tenants.path("total").asInt()).isEqualTo(2);
        assertThat(tenants.path("rows").get(0).toString()).isEqualTo(
                "{\"id\":14,\"name\":\"Nimbus Cloud\",\"tier\":\"Gold\",\"limit\":10000,"
                        + "\"used\":null,\"rejected\":null,\"status\":null}");
    }

    @Test
    void status_filter_marks_rows_unavailable() throws Exception {
        HttpResponse<String> response = timed(() -> HttpProbe.get(port, "/admin/snapshot?status=over"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"rowsAvailable\":false", "\"rows\":null", "\"total\":null");
        assertThat(JSON.readTree(response.body()).path("period").isObject()).isTrue();
    }

    private HttpResponse<String> getAs(String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }

    private static HttpResponse<String> timed(Request request) throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = request.send();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(ANSWER_DEADLINE);
        return response;
    }

    @FunctionalInterface
    private interface Request {
        HttpResponse<String> send() throws Exception;
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
