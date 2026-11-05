package com.mikeflat.gateway.domain.tenant;

public class TenantLoadException extends RuntimeException {

    private final String source;
    private final String problem;

    public TenantLoadException(String source, String problem) {
        this(source, problem, null);
    }

    public TenantLoadException(String source, String problem, Throwable cause) {
        super("Cannot load tenants from " + source + ": " + problem, cause);
        this.source = source;
        this.problem = problem;
    }

    public String source() {
        return source;
    }

    public String problem() {
        return problem;
    }
}
