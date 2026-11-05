package com.mikeflat.gateway.adapters.api.filter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

final class ProblemResponseWriter {

    private static final String UNTYPED_PROBLEM = "about:blank";

    private record Problem(String type, String title, int status, String detail, String instance) {
    }

    private final JsonMapper jsonMapper;

    ProblemResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void write(HttpServletResponse response, HttpStatus status, String detail, String instance) throws IOException {
        Problem problem = new Problem(UNTYPED_PROBLEM, status.getReasonPhrase(), status.value(), detail, instance);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
