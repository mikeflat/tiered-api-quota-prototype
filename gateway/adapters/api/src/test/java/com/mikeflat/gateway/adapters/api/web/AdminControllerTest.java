package com.mikeflat.gateway.adapters.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.app.admin.ChangeTenantTier;
import com.mikeflat.gateway.app.admin.ListTiers;
import com.mikeflat.gateway.app.metrics.LatestTrafficMetrics;
import com.mikeflat.gateway.app.snapshot.BuildAdminSnapshot;
import com.mikeflat.gateway.app.snapshot.TenantUsage;
import com.mikeflat.gateway.app.snapshot.TenantUsageReader;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AdminControllerTest {

    private static final QuotaSettings SETTINGS = new QuotaSettings(
            Duration.ofSeconds(60), Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L), 80);
    private static final Instant NOW = Instant.ofEpochSecond(6_000_045L);
    private static final long PERIOD = 100_000L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TenantRegistry registry = new TenantRegistry(List.of(
            new Tenant(1, "Acme Corp", Tier.BRONZE),
            new Tenant(3, "Cobalt Labs", Tier.BRONZE),
            new Tenant(9, "Ironwood Retail", Tier.SILVER),
            new Tenant(13, "Meridian Bank", Tier.SILVER),
            new Tenant(14, "Nimbus Cloud", Tier.GOLD)));
    private final BlockedTenants blockedTenants = new BlockedTenants();
    private final RecordingUsageReader usageReader = new RecordingUsageReader();
    private final MockMvcTester mockMvc = MockMvcTester.create(MockMvcBuilders
            .standaloneSetup(new AdminController(
                    new ListTiers(SETTINGS),
                    new ChangeTenantTier(registry, blockedTenants, SETTINGS),
                    new BuildAdminSnapshot(
                            registry,
                            SETTINGS,
                            new LatestTrafficMetrics(),
                            usageReader,
                            Clock.fixed(NOW, ZoneOffset.UTC))))
            .setControllerAdvice(new ProblemDetailsAdvice())
            .build());

    @Test
    void tiers_are_listed() {
        MvcTestResult result = mockMvc.get().uri("/admin/tiers").exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyText().isEqualTo(
                "[{\"name\":\"Bronze\",\"limit\":100},{\"name\":\"Silver\",\"limit\":1000},"
                        + "{\"name\":\"Gold\",\"limit\":10000}]");
    }

    @Test
    void tier_change_returns_tenant_with_new_limit() {
        blockedTenants.block(1, PERIOD);

        MvcTestResult result = putTier("1", "{\"tier\":\"gold\"}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyText()
                .isEqualTo("{\"id\":1,\"name\":\"Acme Corp\",\"tier\":\"Gold\",\"limit\":10000}");
        assertThat(registry.find(1)).map(Tenant::tier).contains(Tier.GOLD);
        assertThat(blockedTenants.isBlocked(1, PERIOD)).isFalse();
    }

    @Test
    void unknown_tenant_is_404_problem() {
        MvcTestResult result = putTier("99", "{\"tier\":\"Gold\"}");

        assertThat(result).hasStatus(404).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problem(result).path("status").asInt()).isEqualTo(404);
    }

    @Test
    void unknown_tier_is_400_problem() {
        MvcTestResult result = putTier("1", "{\"tier\":\"Platinum\"}");

        assertThat(result).hasStatus(400).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problem(result).path("detail").asString()).isEqualTo(AdminParameters.UNKNOWN_TIER);
        assertThat(registry.find(1)).map(Tenant::tier).contains(Tier.BRONZE);
    }

    @Test
    void missing_tier_is_400() {
        assertThat(putTier("1", "{}")).hasStatus(400);
        assertThat(registry.find(1)).map(Tenant::tier).contains(Tier.BRONZE);
    }

    @Test
    void malformed_body_is_400() {
        assertThat(putTier("1", "{\"tier\":")).hasStatus(400);
        assertThat(mockMvc.put().uri("/admin/tenants/1/tier").contentType(MediaType.APPLICATION_JSON).exchange())
                .hasStatus(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme", "1.5"})
    void non_numeric_id_is_400(String id) {
        assertThat(putTier(id, "{\"tier\":\"Gold\"}")).hasStatus(400);
    }

    @Test
    void zero_id_is_400() {
        assertThat(putTier("0", "{\"tier\":\"Gold\"}")).hasStatus(400);
        assertThat(mockMvc.get().uri("/admin/snapshot?id=0").exchange()).hasStatus(400);
    }

    @Test
    void snapshot_passes_filters_and_paging() throws Exception {
        usageReader.over.addAll(Set.of(1L, 3L, 9L));
        usageReader.usage.put(3L, new TenantUsage(120, 4));

        MvcTestResult result = mockMvc.get()
                .uri("/admin/snapshot?name=L&tier=bronze&status=OVER&page=0&size=1")
                .exchange();

        assertThat(result).hasStatusOk();
        JsonNode tenants = JSON.readTree(result.getResponse().getContentAsString()).path("tenants");
        assertThat(tenants.path("rowsAvailable").asBoolean()).isTrue();
        assertThat(tenants.path("total").asInt()).isEqualTo(1);
        assertThat(tenants.path("page").asInt()).isZero();
        assertThat(tenants.path("size").asInt()).isEqualTo(1);
        assertThat(tenants.path("rows").toString()).isEqualTo(
                "[{\"id\":3,\"name\":\"Cobalt Labs\",\"tier\":\"Bronze\",\"limit\":100,"
                        + "\"used\":120,\"rejected\":4,\"status\":\"over\"}]");
        assertThat(usageReader.flaggedCalls).containsExactly(Set.of(TenantStatus.OVER));
        assertThat(usageReader.usageCalls).containsExactly(List.of(3L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=x", "tier=Platinum", "status=blocked", "id=acme"})
    void bad_paging_is_400(String query) {
        MvcTestResult result = mockMvc.get().uri("/admin/snapshot?" + query).exchange();

        assertThat(result).hasStatus(400).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void unknown_query_parameter_is_ignored() throws Exception {
        MvcTestResult result = mockMvc.get().uri("/admin/snapshot?sort=used").exchange();

        assertThat(result).hasStatusOk();
        JsonNode rows = JSON.readTree(result.getResponse().getContentAsString()).path("tenants").path("rows");
        assertThat(rows.valueStream().map(row -> row.path("name").asString()))
                .containsExactly("Acme Corp", "Cobalt Labs", "Ironwood Retail", "Meridian Bank", "Nimbus Cloud");
    }

    @Test
    void null_metrics_are_written_as_json_null() throws Exception {
        MvcTestResult result = mockMvc.get().uri("/admin/snapshot").exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"metrics\":null");
        JsonNode period = JSON.readTree(body).path("period");
        assertThat(period.path("index").asLong()).isEqualTo(PERIOD);
        assertThat(period.path("secondsLeft").asLong()).isEqualTo(15);
        assertThat(Instant.parse(period.path("endsAt").asString())).isEqualTo(Instant.ofEpochSecond(6_000_060L));
    }

    @Test
    void unavailable_status_rows_are_written_as_json_null() throws Exception {
        usageReader.down = true;

        String body = mockMvc.get().uri("/admin/snapshot?status=over").exchange().getResponse().getContentAsString();

        assertThat(body).contains("\"rowsAvailable\":false", "\"rows\":null", "\"total\":null");
    }

    @ControllerAdvice
    static final class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {
    }

    private MvcTestResult putTier(String id, String body) {
        return mockMvc.put()
                .uri("/admin/tenants/" + id + "/tier")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static JsonNode problem(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingUsageReader implements TenantUsageReader {
        final Map<Long, TenantUsage> usage = new HashMap<>();
        final Set<Long> over = new java.util.HashSet<>();
        final List<List<Long>> usageCalls = new ArrayList<>();
        final List<Set<TenantStatus>> flaggedCalls = new ArrayList<>();
        boolean down;

        @Override
        public Map<Long, TenantUsage> usageOf(long periodIndex, List<Long> tenantIds) {
            usageCalls.add(List.copyOf(tenantIds));
            failIfDown();
            Map<Long, TenantUsage> result = new HashMap<>();
            tenantIds.forEach(id -> result.put(id, usage.getOrDefault(id, TenantUsage.NONE)));
            return result;
        }

        @Override
        public Set<Long> flaggedTenants(long periodIndex, Set<TenantStatus> statuses) {
            flaggedCalls.add(Set.copyOf(statuses));
            failIfDown();
            return statuses.contains(TenantStatus.OVER) ? Set.copyOf(over) : Set.of();
        }

        private void failIfDown() {
            if (down) {
                throw new com.mikeflat.gateway.app.snapshot.UsageReadFailedException(
                        "redis down", new IllegalStateException());
            }
        }
    }
}
