package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CustomTenantsFileBusinessEndpointTest {

    private static final String TWO_TENANTS = """
            [
              { "id": 1, "name": "Acme Corp", "tier": "Bronze" },
              { "id": 2, "name": "Bluebird Analytics", "tier": "Gold" }
            ]
            """;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void twoTenantsFile(DynamicPropertyRegistry registry) {
        registry.add("tenants.file", CustomTenantsFileBusinessEndpointTest::writeTwoTenantsFile);
    }

    @Test
    void tenant_from_the_loaded_file_is_admitted() throws Exception {
        assertThat(HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", "2")).statusCode())
                .isEqualTo(200);
    }

    @Test
    void tenant_absent_from_the_loaded_file_is_rejected() throws Exception {
        assertThat(HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", "3")).statusCode())
                .isEqualTo(400);
    }

    private static String writeTwoTenantsFile() {
        try {
            Path file = Files.createTempFile("two-tenants", ".json");
            file.toFile().deleteOnExit();
            Files.writeString(file, TWO_TENANTS, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
