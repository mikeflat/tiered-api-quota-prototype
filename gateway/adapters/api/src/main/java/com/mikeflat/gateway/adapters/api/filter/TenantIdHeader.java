package com.mikeflat.gateway.adapters.api.filter;

sealed interface TenantIdHeader {

    int MAX_DIGITS = 18;

    record Missing() implements TenantIdHeader {
    }

    record Malformed() implements TenantIdHeader {
    }

    record Present(long tenantId) implements TenantIdHeader {
    }

    static TenantIdHeader parse(String value) {
        if (value == null || value.isBlank()) {
            return new Missing();
        }
        if (!isAsciiDigitsWithinLimit(value)) {
            return new Malformed();
        }
        return new Present(Long.parseLong(value));
    }

    private static boolean isAsciiDigitsWithinLimit(String value) {
        if (value.length() > MAX_DIGITS) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
