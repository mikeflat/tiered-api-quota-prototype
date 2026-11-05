package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The server listens on the port given by SERVER_PORT. */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
class ServerPortTest {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        registry.add("SERVER_PORT", () -> PORT);
    }

    @Test
    void health_answers_on_configured_port() throws Exception {
        assertThat(HttpProbe.get(PORT, "/actuator/health").statusCode()).isEqualTo(200);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
