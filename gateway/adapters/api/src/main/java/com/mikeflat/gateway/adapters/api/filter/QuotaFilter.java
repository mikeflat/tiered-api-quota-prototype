package com.mikeflat.gateway.adapters.api.filter;

import com.mikeflat.gateway.app.quota.AdmitBusinessCall;
import com.mikeflat.gateway.domain.quota.Admission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public final class QuotaFilter extends OncePerRequestFilter {

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    static final String TENANT_MISSING_DETAIL = "Missing X-Tenant-Id header";
    static final String TENANT_UNKNOWN_DETAIL = "X-Tenant-Id does not identify a known tenant";

    private final AdmitBusinessCall admitBusinessCall;
    private final ProblemResponseWriter problemWriter;

    public QuotaFilter(AdmitBusinessCall admitBusinessCall, JsonMapper jsonMapper) {
        this.admitBusinessCall = admitBusinessCall;
        this.problemWriter = new ProblemResponseWriter(jsonMapper);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (TenantIdHeader.parse(request.getHeader(TENANT_ID_HEADER))) {
            case TenantIdHeader.Missing missing -> rejectAsBadRequest(request, response, TENANT_MISSING_DETAIL);
            case TenantIdHeader.Malformed malformed -> rejectAsBadRequest(request, response, TENANT_UNKNOWN_DETAIL);
            case TenantIdHeader.Present present -> admit(present.tenantId(), request, response, chain);
        }
    }

    private void admit(long tenantId, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (admitBusinessCall.admit(tenantId)) {
            case Admission.Admitted admitted -> chain.doFilter(request, response);
            case Admission.TenantUnknown unknown -> rejectAsBadRequest(request, response, TENANT_UNKNOWN_DETAIL);
        }
    }

    private void rejectAsBadRequest(HttpServletRequest request, HttpServletResponse response, String detail)
            throws IOException {
        problemWriter.write(response, HttpStatus.BAD_REQUEST, detail, request.getRequestURI());
    }
}
