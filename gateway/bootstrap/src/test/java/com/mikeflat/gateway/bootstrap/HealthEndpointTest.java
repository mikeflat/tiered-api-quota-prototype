package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void health_reports_up_as_json_without_credentials() throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        // Actuator answers with application/vnd.spring-boot.actuator.v3+json, a JSON media type.
        MediaType contentType = MediaType.parseMediaType(
                response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(contentType.getSubtype().equals("json") || "json".equals(contentType.getSubtypeSuffix()))
                .as("JSON media type, got %s", contentType)
                .isTrue();
        assertThat(response.body()).contains("\"status\":\"UP\"");
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
        assertThat(HttpProbe.get(port, "/admin/snapshot").statusCode()).isEqualTo(404);
    }
}
