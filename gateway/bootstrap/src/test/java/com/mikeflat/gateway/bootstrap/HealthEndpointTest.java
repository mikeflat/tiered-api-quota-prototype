package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class HealthEndpointTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    @Value("${local.server.port}")
    private int port;

    @Test
    void health_reports_up_as_json_without_credentials() throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(isJson(response)).as("JSON media type, got %s", contentType(response)).isTrue();
        assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("UP");
    }

    @Test
    void health_lists_redis_component_with_status_only() throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/actuator/health");

        JsonNode redis = JSON.readTree(response.body()).path("components").path("redis");
        assertThat(redis.propertyNames()).containsExactly("status");
        assertThat(redis.path("status").asString()).isEqualTo("UP");
    }

    @Test
    void health_reveals_no_redis_details() throws Exception {
        String body = HttpProbe.get(port, "/actuator/health").body();

        assertThat(body)
                .doesNotContain(REDIS.getHost())
                .doesNotContain(Integer.toString(REDIS.getMappedPort(6379)))
                .doesNotContain("version")
                .doesNotContain("details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void probe_is_up_without_redis_component(String probe) throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, probe);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").has("redis")).isFalse();
    }

    @Test
    void environment_endpoint_is_not_exposed() throws Exception {
        assertThat(HttpProbe.get(port, "/actuator/env").statusCode()).isEqualTo(404);
    }

    @Test
    void beans_endpoint_is_not_exposed() throws Exception {
        assertThat(HttpProbe.get(port, "/actuator/beans").statusCode()).isEqualTo(404);
    }

    @Test
    void path_that_does_not_exist_yet_is_not_found() throws Exception {
        assertThat(HttpProbe.get(port, "/admin/tenants").statusCode()).isEqualTo(404);
    }

    private static boolean isJson(HttpResponse<String> response) {
        MediaType contentType = contentType(response);
        return contentType.getSubtype().equals("json") || "json".equals(contentType.getSubtypeSuffix());
    }

    private static MediaType contentType(HttpResponse<String> response) {
        return MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
    }
}
