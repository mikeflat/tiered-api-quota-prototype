package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BusinessEndpointTest {

    private static final String RESOURCE = "/api/v1/resource";
    private static final String MOCK_PAYLOAD =
            "{\"id\":\"mock-resource\",\"message\":\"Served by the quota gateway stub\"}";
    private static final String BRONZE_TENANT = "1";
    private static final String GOLD_TENANT = "14";
    private static final String MISSING_DETAIL = "Missing X-Tenant-Id header";
    private static final String UNKNOWN_DETAIL = "X-Tenant-Id does not identify a known tenant";

    @Value("${local.server.port}")
    private int port;

    @Test
    void known_tenant_gets_the_mock_payload() throws Exception {
        HttpResponse<String> response = getAs(BRONZE_TENANT, RESOURCE);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(contentType(response).equalsTypeAndSubtype(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.body()).isEqualTo(MOCK_PAYLOAD);
    }

    @Test
    void payload_does_not_depend_on_the_tenant() throws Exception {
        HttpResponse<String> bronze = getAs(BRONZE_TENANT, RESOURCE);
        HttpResponse<String> gold = getAs(GOLD_TENANT, RESOURCE);

        assertThat(bronze.statusCode()).isEqualTo(200);
        assertThat(gold.statusCode()).isEqualTo(200);
        assertThat(gold.body()).isEqualTo(bronze.body());
    }

    @Test
    void success_carries_no_rate_limit_headers() throws Exception {
        HttpResponse<String> response = getAs(BRONZE_TENANT, RESOURCE);

        assertThat(response.headers().map().keySet())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .noneMatch(name -> name.startsWith("x-ratelimit")
                        || name.startsWith("ratelimit")
                        || name.equals("x-quota"));
    }

    @Test
    void missing_header_is_bad_request_with_problem_body() throws Exception {
        assertProblem(HttpProbe.get(port, RESOURCE), MISSING_DETAIL);
    }

    @Test
    void empty_header_is_bad_request() throws Exception {
        assertThat(getAs("", RESOURCE).statusCode()).isEqualTo(400);
    }

    @Test
    void unknown_tenant_is_bad_request_with_problem_body() throws Exception {
        assertProblem(getAs("999", RESOURCE), UNKNOWN_DETAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme", "0", "-1", "1.5", "99999999999999999999"})
    void id_that_is_not_a_positive_whole_number_is_bad_request(String tenantId) throws Exception {
        assertProblem(getAs(tenantId, RESOURCE), UNKNOWN_DETAIL);
    }

    @Test
    void rejection_does_not_echo_the_header_value() throws Exception {
        HttpResponse<String> response = getAs("<script>", RESOURCE);

        assertProblem(response, UNKNOWN_DETAIL);
        assertThat(response.body()).doesNotContain("<script>");
    }

    @Test
    void unknown_business_path_without_tenant_is_bad_request() throws Exception {
        assertThat(HttpProbe.get(port, "/api/v1/other").statusCode()).isEqualTo(400);
    }

    @Test
    void unknown_business_path_with_known_tenant_is_not_found() throws Exception {
        assertThat(getAs(BRONZE_TENANT, "/api/v1/other").statusCode()).isEqualTo(404);
    }

    @Test
    void other_api_version_with_known_tenant_is_not_found() throws Exception {
        assertThat(getAs(BRONZE_TENANT, "/api/v2/resource").statusCode()).isEqualTo(404);
    }

    @Test
    void path_that_only_shares_the_prefix_is_not_guarded() throws Exception {
        assertThat(HttpProbe.get(port, "/apix/resource").statusCode()).isEqualTo(404);
    }

    @Test
    void health_needs_no_tenant_header() throws Exception {
        assertThat(HttpProbe.get(port, "/actuator/health").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> getAs(String tenantId, String path) throws Exception {
        return HttpProbe.get(port, path, Map.of("X-Tenant-Id", tenantId));
    }

    private static MediaType contentType(HttpResponse<String> response) {
        return MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
    }

    private static void assertProblem(HttpResponse<String> response, String detail) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(contentType(response).equalsTypeAndSubtype(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.body())
                .contains("\"type\":\"about:blank\"")
                .contains("\"title\":\"Bad Request\"")
                .contains("\"status\":400")
                .contains("\"detail\":\"" + detail + "\"")
                .contains("\"instance\":\"" + RESOURCE + "\"");
    }
}
