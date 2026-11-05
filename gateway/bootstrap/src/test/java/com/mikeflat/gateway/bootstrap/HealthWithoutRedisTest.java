package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthWithoutRedisTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> CLOSED_PORT);
    }

    @Value("${local.server.port}")
    private int port;

    @Test
    void health_is_down_with_redis_down() throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(503);
        MediaType contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(contentType.getSubtype().equals("json") || "json".equals(contentType.getSubtypeSuffix()))
                .as("JSON media type, got %s", contentType)
                .isTrue();
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("DOWN");
        JsonNode redis = body.path("components").path("redis");
        assertThat(redis.propertyNames()).containsExactly("status");
        assertThat(redis.path("status").asString()).isEqualTo("DOWN");
    }

    @Test
    void health_reveals_no_redis_error() throws Exception {
        String body = HttpProbe.get(port, "/actuator/health").body();

        assertThat(body)
                .doesNotContain("error")
                .doesNotContain("Unable to connect")
                .doesNotContain(Integer.toString(CLOSED_PORT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void probe_is_up_while_redis_is_unreachable(String probe) throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, probe);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("UP");
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
