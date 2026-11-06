package com.mikeflat.gateway.domain.tenant;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record TenantFilter(Optional<String> nameContains, Optional<Long> id, Optional<Tier> tier) {

    private static final TenantFilter NONE = new TenantFilter(Optional.empty(), Optional.empty(), Optional.empty());

    public TenantFilter {
        Objects.requireNonNull(nameContains, "nameContains");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tier, "tier");
        nameContains = nameContains.filter(text -> !text.isBlank());
    }

    public static TenantFilter none() {
        return NONE;
    }

    public TenantFilter withNameContaining(String text) {
        return new TenantFilter(Optional.ofNullable(text), id, tier);
    }

    public TenantFilter withId(long tenantId) {
        return new TenantFilter(nameContains, Optional.of(tenantId), tier);
    }

    public TenantFilter withTier(Tier wantedTier) {
        return new TenantFilter(nameContains, id, Optional.of(wantedTier));
    }

    public boolean matches(Tenant tenant) {
        return nameContains.map(text -> containsIgnoringCase(tenant.name(), text)).orElse(true)
                && id.map(wantedId -> wantedId == tenant.id()).orElse(true)
                && tier.map(wantedTier -> wantedTier == tenant.tier()).orElse(true);
    }

    private static boolean containsIgnoringCase(String name, String text) {
        return name.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }
}
