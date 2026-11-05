package com.mikeflat.gateway.adapters.api.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mikeflat.gateway.app.quota.AdmitBusinessCall;
import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.app.usage.UsageRecordingAlerts;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class QuotaFilterTest {

    private static final String PATH = "/api/v1/resource";

    private static final QuotaSettings SIXTY_SECOND_PERIOD = new QuotaSettings(
            Duration.ofSeconds(60),
            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L),
            80);
    private static final Instant ARRIVAL = Instant.ofEpochSecond(1_800_000_030L);
    private static final long PERIOD = 30_000_000L;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final BlockedTenants blockedTenants = new BlockedTenants();
    private final QuotaFilter filter = new QuotaFilter(
            new AdmitBusinessCall(
                    new AdmissionPolicy(
                            new TenantRegistry(List.of(new Tenant(1, "Acme Corp", Tier.BRONZE))),
                            blockedTenants,
                            SIXTY_SECOND_PERIOD),
                    new UsageRecorder(event -> 1, Runnable::run, (event, used) -> {
                    }, mock(UsageRecordingAlerts.class)),
                    SIXTY_SECOND_PERIOD,
                    Clock.fixed(ARRIVAL, ZoneOffset.UTC)),
            jsonMapper);
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @Test
    void known_tenant_continues_the_chain() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithTenant("1");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void missing_header_is_rejected_with_missing_detail() throws ServletException, IOException {
        filter.doFilter(new MockHttpServletRequest("GET", PATH), response, chain);

        assertRejectedWith(QuotaFilter.TENANT_MISSING_DETAIL);
    }

    @Test
    void empty_header_is_rejected_with_missing_detail() throws ServletException, IOException {
        filter.doFilter(requestWithTenant(""), response, chain);

        assertRejectedWith(QuotaFilter.TENANT_MISSING_DETAIL);
    }

    @Test
    void unknown_tenant_is_rejected_with_unknown_detail() throws ServletException, IOException {
        filter.doFilter(requestWithTenant("999"), response, chain);

        assertRejectedWith(QuotaFilter.TENANT_UNKNOWN_DETAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme", "0", "-1", "1.5", "99999999999999999999", "<script>"})
    void malformed_id_is_rejected_with_unknown_detail(String header) throws ServletException, IOException {
        filter.doFilter(requestWithTenant(header), response, chain);

        assertRejectedWith(QuotaFilter.TENANT_UNKNOWN_DETAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "acme-tenant", "12345678901234567890"})
    void rejection_body_does_not_echo_the_header_value(String header) throws ServletException, IOException {
        filter.doFilter(requestWithTenant(header), response, chain);

        assertThat(response.getContentAsString()).doesNotContain(header);
    }

    @Test
    void blocked_tenant_gets_too_many_requests() throws ServletException, IOException {
        blockedTenants.block(1, PERIOD);

        filter.doFilter(requestWithTenant("1"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void blocked_response_carries_retry_after_seconds() throws ServletException, IOException {
        blockedTenants.block(1, PERIOD);

        filter.doFilter(requestWithTenant("1"), response, chain);

        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
    }

    @Test
    void blocked_response_is_problem_json_without_usage_numbers() throws ServletException, IOException {
        blockedTenants.block(1, PERIOD);

        filter.doFilter(requestWithTenant("1"), response, chain);

        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("title").asString()).isEqualTo("Too Many Requests");
        assertThat(body.get("detail").asString()).isEqualTo(QuotaFilter.QUOTA_EXHAUSTED_DETAIL);
        assertThat(body.get("instance").asString()).isEqualTo(PATH);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance");
        assertThat(response.getHeaderNames())
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("ratelimit")
                        || name.toLowerCase(Locale.ROOT).startsWith("x-quota"));
    }

    @Test
    void blocked_request_does_not_reach_handler() throws ServletException, IOException {
        blockedTenants.block(1, PERIOD);

        filter.doFilter(requestWithTenant("1"), response, chain);

        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest requestWithTenant(String tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader(QuotaFilter.TENANT_ID_HEADER, tenantId);
        return request;
    }

    private void assertRejectedWith(String detail) throws IOException {
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("title").asString()).isEqualTo("Bad Request");
        assertThat(body.get("detail").asString()).isEqualTo(detail);
        assertThat(body.get("instance").asString()).isEqualTo(PATH);
    }
}
