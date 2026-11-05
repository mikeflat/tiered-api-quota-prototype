package com.mikeflat.gateway.adapters.api.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.app.quota.AdmitBusinessCall;
import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final QuotaFilter filter = new QuotaFilter(
            new AdmitBusinessCall(
                    new AdmissionPolicy(new TenantRegistry(List.of(new Tenant(1, "Acme Corp", Tier.BRONZE)))),
                    new UsageRecorder(event -> 1, Runnable::run),
                    new QuotaSettings(
                            Duration.ofSeconds(60),
                            Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L),
                            80),
                    Clock.systemUTC()),
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
