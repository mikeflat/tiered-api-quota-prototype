package com.mikeflat.gateway.adapters.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class StubControllerTest {

    private final MockMvcTester mockMvc = MockMvcTester.of(new StubController());

    @Test
    void returns_the_fixed_mock_resource_as_json() {
        MvcTestResult result = mockMvc.get().uri("/api/v1/resource").header("X-Tenant-Id", "1").exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyText()
                .isEqualTo("{\"id\":\"mock-resource\",\"message\":\"Served by the quota gateway stub\"}");
    }
}
