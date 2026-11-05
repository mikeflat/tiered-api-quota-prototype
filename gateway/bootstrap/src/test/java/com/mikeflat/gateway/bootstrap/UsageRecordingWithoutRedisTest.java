package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UsageRecordingWithoutRedisTest {

    private static final int CLOSED_PORT = closedPort();
    private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration PROMPT_CLOSE = Duration.ofSeconds(3);

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> CLOSED_PORT);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private UsageRecorder usageRecorder;

    @Test
    void requests_succeed_while_redis_is_unreachable() throws Exception {
        for (int i = 0; i < 5; i++) {
            long started = System.nanoTime();

            HttpResponse<String> response = getAs(port, "1");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("mock-resource");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(REDIS_TIMEOUT);
        }
    }

    @Test
    void failed_events_are_counted_while_redis_is_unreachable() throws Exception {
        long failedBefore = usageRecorder.stats().failed();
        long recordedBefore = usageRecorder.stats().recorded();

        for (int i = 0; i < 3; i++) {
            assertThat(getAs(port, "2").statusCode()).isEqualTo(200);
        }

        Eventually.within(Duration.ofSeconds(15),
                () -> assertThat(usageRecorder.stats().failed()).isGreaterThanOrEqualTo(failedBefore + 3));
        assertThat(usageRecorder.stats().recorded()).isEqualTo(recordedBefore);
    }

    @Test
    void context_closes_promptly_while_redis_is_unreachable() throws Exception {
        try (ServerSocket unresponsiveRedis = new ServerSocket(0)) {
            ConfigurableApplicationContext gateway = SpringApplication.run(GatewayApplication.class,
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--spring.data.redis.host=localhost",
                    "--spring.data.redis.port=" + unresponsiveRedis.getLocalPort());
            int gatewayPort = gateway.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            for (int i = 0; i < 10; i++) {
                assertThat(getAs(gatewayPort, "3").statusCode()).isEqualTo(200);
            }

            long started = System.nanoTime();
            gateway.close();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(PROMPT_CLOSE);
        }
    }

    @Test
    void context_closes_promptly_while_a_redis_call_hangs() throws Exception {
        SpringApplication application = new SpringApplication(GatewayApplication.class, HangingRedisCall.class);
        ConfigurableApplicationContext gateway = application.run("--server.port=0", "--spring.main.banner-mode=off");
        int gatewayPort = gateway.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        for (int i = 0; i < 3; i++) {
            assertThat(getAs(gatewayPort, "4").statusCode()).isEqualTo(200);
        }
        assertThat(HangingRedisCall.entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertTimeoutPreemptively(PROMPT_CLOSE, gateway::close);
    }

    static class HangingRedisCall {

        static final CountDownLatch entered = new CountDownLatch(1);

        @Bean
        @Primary
        UsageCounter hangingUsageCounter() {
            return event -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new UsageRecordingFailedException("redis call interrupted", null);
            };
        }
    }

    private static HttpResponse<String> getAs(int port, String tenantId) throws Exception {
        return HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId));
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
