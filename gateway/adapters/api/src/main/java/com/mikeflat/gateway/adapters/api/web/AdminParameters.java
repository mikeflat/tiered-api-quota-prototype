package com.mikeflat.gateway.adapters.api.web;

import com.mikeflat.gateway.app.snapshot.PageRequest;
import com.mikeflat.gateway.app.snapshot.SnapshotQuery;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.tenant.TenantFilter;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AdminParameters {

    static final String UNKNOWN_TIER = "tier must be one of Bronze, Silver or Gold";
    static final String MISSING_TIER = "tier is required";
    static final String UNKNOWN_STATUS = "status must be one of ok, near or over";
    static final String BAD_ID = "id must be a positive integer";

    private AdminParameters() {
    }

    static Tier requiredTier(String value) {
        return optionalTier(value).orElseThrow(() -> badRequest(MISSING_TIER));
    }

    static Optional<Tier> optionalTier(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Tier.parse(value.strip()));
        } catch (IllegalArgumentException unknownTier) {
            throw badRequest(UNKNOWN_TIER);
        }
    }

    static Optional<TenantStatus> optionalStatus(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "ok" -> Optional.of(TenantStatus.OK);
            case "near" -> Optional.of(TenantStatus.NEAR);
            case "over" -> Optional.of(TenantStatus.OVER);
            default -> throw badRequest(UNKNOWN_STATUS);
        };
    }

    static long tenantId(long id) {
        if (id < 1) {
            throw badRequest(BAD_ID);
        }
        return id;
    }

    static PageRequest pageRequest(Integer page, Integer size) {
        try {
            return new PageRequest(
                    page == null ? PageRequest.DEFAULT_PAGE : page,
                    size == null ? PageRequest.DEFAULT_SIZE : size);
        } catch (IllegalArgumentException outOfRange) {
            throw badRequest(outOfRange.getMessage());
        }
    }

    static SnapshotQuery snapshotQuery(
            String name, Long id, String tier, String status, Integer page, Integer size) {
        TenantFilter filter = TenantFilter.none().withNameContaining(name);
        if (id != null) {
            filter = filter.withId(tenantId(id));
        }
        filter = optionalTier(tier).map(filter::withTier).orElse(filter);
        return new SnapshotQuery(filter, optionalStatus(status), pageRequest(page, size));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
