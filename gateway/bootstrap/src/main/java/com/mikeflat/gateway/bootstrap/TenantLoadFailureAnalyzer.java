package com.mikeflat.gateway.bootstrap;

import com.mikeflat.gateway.domain.tenant.TenantLoadException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public class TenantLoadFailureAnalyzer extends AbstractFailureAnalyzer<TenantLoadException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, TenantLoadException cause) {
        String description = """
                The tenants could not be loaded.

                    Tenants file: %s
                    Problem: %s
                """.formatted(cause.source(), cause.problem());
        String action = """
                Check TENANTS_FILE: it must point at a readable JSON array of tenant objects, each \
                with a non-blank "id" (unique), "name" and "tier" (Bronze, Silver or Gold).""";
        return new FailureAnalysis(description, action, cause);
    }
}
