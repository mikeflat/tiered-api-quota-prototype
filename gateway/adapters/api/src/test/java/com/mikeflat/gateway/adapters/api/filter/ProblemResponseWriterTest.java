package com.mikeflat.gateway.adapters.api.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ProblemResponseWriterTest {

    private final ProblemResponseWriter writer = new ProblemResponseWriter(JsonMapper.builder().build());

    @Test
    void writes_problem_json_with_status_title_detail_and_instance() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, HttpStatus.BAD_REQUEST, "Missing X-Tenant-Id header", "/api/v1/resource");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                        + "\"detail\":\"Missing X-Tenant-Id header\",\"instance\":\"/api/v1/resource\"}");
    }
}
