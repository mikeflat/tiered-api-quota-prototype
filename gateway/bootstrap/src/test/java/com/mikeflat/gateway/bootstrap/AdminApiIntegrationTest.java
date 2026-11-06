package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.BlockedTenants;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"quota.limits.bronze=5", "quota.limits.silver=50"})
@Testcontainers
class AdminApiIntegrationTest {

    private static final long PERIOD_SECONDS = 60;
    private static final long FIRST_PERIOD = 30_000_000L;
    private static final long PERIODS_PER_TEST = 10;
    private static final long SECONDS_INTO_PERIOD = 30;
    private static final int BRONZE_LIMIT = 5;
    private static final int BRONZE_NEAR_BOUNDARY = 4;
    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(10);
    private static final Duration METRICS_DEADLINE = Duration.ofSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
    private long arrivalSecond;

    @BeforeEach
    void emptyDatabaseAndFreshPeriod() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        period = FIRST_PERIOD + testsStarted.getAndIncrement() * PERIODS_PER_TEST;
        arrivalSecond = period * PERIOD_SECONDS + SECONDS_INTO_PERIOD;
        clock.set(Instant.ofEpochSecond(arrivalSecond));
    }

    @Test
    void tiers_are_listed_with_configured_limits() throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/admin/tiers");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(
                "[{\"name\":\"Bronze\",\"limit\":5},{\"name\":\"Silver\",\"limit\":50},"
                        + "{\"name\":\"Gold\",\"limit\":10000}]");
    }

    @Test
    void snapshot_has_period_metrics_and_first_page() throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/admin/snapshot");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("period", "metrics", "tenants");
        assertThat(body.path("period").path("index").asLong()).isEqualTo(period);
        assertThat(body.path("period").path("secondsLeft").asLong()).isEqualTo(30);
        assertThat(Instant.parse(body.path("period").path("endsAt").asString()))
                .isEqualTo(Instant.ofEpochSecond((period + 1) * PERIOD_SECONDS));
        JsonNode tenants = body.path("tenants");
        assertThat(tenants.path("rowsAvailable").asBoolean()).isTrue();
        assertThat(tenants.path("total").asInt()).isEqualTo(15);
        assertThat(tenants.path("page").asInt()).isZero();
        assertThat(tenants.path("size").asInt()).isEqualTo(10);
        assertThat(names(tenants)).containsExactly(
                "Acme Corp", "Bluebird Analytics", "Cobalt Labs", "Driftwood Travel", "Ember Foods",
                "Fjord Logistics", "Granite Insurance", "Harbor Health", "Ironwood Retail", "Juniper Media");
        Eventually.within(METRICS_DEADLINE, () -> {
            JsonNode metrics = snapshot("").path("metrics");
            assertThat(metrics.isObject()).isTrue();
            assertThat(metrics.path("perSecond").size()).isEqualTo(60);
        });
    }

    @Test
    void traffic_sent_over_http_shows_in_metrics_within_two_seconds() throws Exception {
        sendRecordedRequests("2", 3);
        clock.set(Instant.ofEpochSecond(arrivalSecond + 1));

        Eventually.within(METRICS_DEADLINE, () -> {
            JsonNode metrics = snapshot("").path("metrics");
            assertThat(metrics.path("perSecond").size()).isEqualTo(60);
            JsonNode lastSecond = metrics.path("perSecond").get(59);
            assertThat(lastSecond.path("second").asLong()).isEqualTo(arrivalSecond);
            assertThat(lastSecond.path("allowed").asLong()).isEqualTo(3);
            assertThat(lastSecond.path("rejected").asLong()).isZero();
            assertThat(metrics.path("totals").path("allowed").asLong()).isEqualTo(3);
            assertThat(metrics.path("totals").path("rejected").asLong()).isZero();
        });
    }

    @Test
    void rows_carry_usage_and_status_against_current_limit() throws Exception {
        sendRecordedRequests("1", BRONZE_NEAR_BOUNDARY);

        JsonNode row = snapshot("?id=1").path("tenants").path("rows").get(0);

        assertThat(row.toString()).isEqualTo(
                "{\"id\":1,\"name\":\"Acme Corp\",\"tier\":\"Bronze\",\"limit\":5,"
                        + "\"used\":4,\"rejected\":0,\"status\":\"near\"}");
        assertThat(snapshot("?id=2").path("tenants").path("rows").get(0).path("status").asString()).isEqualTo("ok");
    }

    @Test
    void name_id_and_tier_filters_combine() throws Exception {
        assertThat(names(snapshot("?name=BANK").path("tenants"))).containsExactly("Meridian Bank");
        assertThat(names(snapshot("?id=1").path("tenants"))).containsExactly("Acme Corp");
        JsonNode silver = snapshot("?tier=silver").path("tenants");
        assertThat(silver.path("total").asInt()).isEqualTo(5);
        assertThat(silver.path("rows").valueStream().map(row -> row.path("tier").asString())).containsOnly("Silver");
        assertThat(names(snapshot("?tier=Bronze&name=co").path("tenants"))).containsExactly("Acme Corp", "Cobalt Labs");
        JsonNode none = snapshot("?id=99").path("tenants");
        assertThat(none.path("rows").size()).isZero();
        assertThat(none.path("total").asInt()).isZero();
        assertThat(snapshot("?name=&tier=").path("tenants")).isEqualTo(snapshot("").path("tenants"));
    }

    @Test
    void status_filters_use_near_and_over_sets() throws Exception {
        sendRecordedRequests("3", BRONZE_LIMIT);
        sendRecordedRequests("1", BRONZE_LIMIT);
        sendRecordedRequests("2", BRONZE_NEAR_BOUNDARY);

        JsonNode over = snapshot("?status=over").path("tenants");
        assertThat(over.path("total").asInt()).isEqualTo(2);
        assertThat(ids(over)).containsExactly(1L, 3L);
        assertThat(ids(snapshot("?status=NEAR").path("tenants"))).containsExactly(2L);
        assertThat(ids(snapshot("?status=over&name=acme").path("tenants"))).containsExactly(1L);
        JsonNode ok = snapshot("?status=ok&size=100").path("tenants");
        assertThat(ok.path("total").asInt()).isEqualTo(12);
        assertThat(ids(ok)).doesNotContain(1L, 2L, 3L);
    }

    @Test
    void rows_are_paged_by_offset() throws Exception {
        JsonNode second = snapshot("?page=1").path("tenants");
        assertThat(names(second)).containsExactly(
                "Kestrel Payments", "Lumen Energy", "Meridian Bank", "Nimbus Cloud", "Orbit Telecom");
        assertThat(second.path("total").asInt()).isEqualTo(15);

        JsonNode custom = snapshot("?size=3&page=1").path("tenants");
        assertThat(names(custom)).containsExactly("Driftwood Travel", "Ember Foods", "Fjord Logistics");
        assertThat(custom.path("size").asInt()).isEqualTo(3);

        JsonNode pastEnd = snapshot("?page=5").path("tenants");
        assertThat(pastEnd.path("rows").size()).isZero();
        assertThat(pastEnd.path("total").asInt()).isEqualTo(15);
        assertThat(pastEnd.path("page").asInt()).isEqualTo(5);

        assertThat(names(snapshot("?sort=used").path("tenants")).getFirst()).isEqualTo("Acme Corp");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "tier=Platinum", "status=blocked", "page=-1", "size=0", "size=101", "page=x", "id=acme", "id=0"})
    void invalid_snapshot_parameters_are_400_problem(String query) throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/admin/snapshot?" + query);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(JSON.readTree(response.body()).path("status").asInt()).isEqualTo(400);
    }

    @Test
    void snapshot_requests_do_not_change_quota_or_traffic_keys() throws Exception {
        sendRecordedRequests("1", BRONZE_LIMIT);
        sendRecordedRequests("2", BRONZE_NEAR_BOUNDARY);
        Map<String, String> before = dumpQuotaAndTrafficKeys();

        for (int i = 0; i < 500; i++) {
            assertThat(HttpProbe.get(port, "/admin/snapshot" + (i % 2 == 0 ? "" : "?status=over")).statusCode())
                    .isEqualTo(200);
        }

        assertThat(dumpQuotaAndTrafficKeys()).isEqualTo(before);
        assertThat(blockedTenants.isBlocked(2, period)).isFalse();
    }

    private JsonNode snapshot(String query) throws Exception {
        HttpResponse<String> response = HttpProbe.get(port, "/admin/snapshot" + query);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static List<String> names(JsonNode tenants) {
        return tenants.path("rows").valueStream().map(row -> row.path("name").asString()).toList();
    }

    private static List<Long> ids(JsonNode tenants) {
        return tenants.path("rows").valueStream().map(row -> row.path("id").asLong()).toList();
    }

    private Map<String, String> dumpQuotaAndTrafficKeys() {
        Map<String, String> dump = new TreeMap<>();
        for (String pattern : List.of("quota:*", "traffic:*")) {
            for (String key : redis.keys(pattern)) {
                String value = redis.type(key) == DataType.SET
                        ? new java.util.TreeSet<>(redis.opsForSet().members(key)).toString()
                        : redis.opsForValue().get(key);
                dump.put(key, value);
            }
        }
        return dump;
    }

    private void sendRecordedRequests(String tenantId, int count) throws Exception {
        for (int i = 1; i <= count; i++) {
            assertThat(HttpProbe.get(port, "/api/v1/resource", Map.of("X-Tenant-Id", tenantId)).statusCode())
                    .isEqualTo(200);
            String expectedUsed = Integer.toString(i);
            Eventually.within(RECORDING_DEADLINE, () -> assertThat(
                    redis.opsForValue().get("quota:used:" + tenantId + ":" + period)).isEqualTo(expectedUsed));
        }
    }
}
