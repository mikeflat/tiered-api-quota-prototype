package com.mikeflat.gateway.domain.tenant;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TenantRegistry {

    private static final Comparator<Tenant> BY_NAME = Comparator
            .comparing(Tenant::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(Tenant::id);

    private final ConcurrentHashMap<Long, Tenant> tenantsById;

    public TenantRegistry(Collection<Tenant> tenants) {
        if (tenants == null || tenants.isEmpty()) {
            throw new IllegalArgumentException("no tenants defined");
        }
        ConcurrentHashMap<Long, Tenant> byId = new ConcurrentHashMap<>(tenants.size() * 2);
        for (Tenant tenant : tenants) {
            if (byId.putIfAbsent(tenant.id(), tenant) != null) {
                throw new IllegalArgumentException("duplicate tenant id " + tenant.id());
            }
        }
        this.tenantsById = byId;
    }

    public Optional<Tenant> find(long id) {
        return Optional.ofNullable(tenantsById.get(id));
    }

    public List<Tenant> listByName() {
        return tenantsById.values().stream().sorted(BY_NAME).toList();
    }

    public List<Tenant> search(TenantFilter filter) {
        return tenantsById.values().stream().filter(filter::matches).sorted(BY_NAME).toList();
    }

    public Optional<Tenant> changeTier(long id, Tier tier) {
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        return Optional.ofNullable(tenantsById.computeIfPresent(id, (key, tenant) -> tenant.withTier(tier)));
    }

    public Map<Tier, Integer> countByTier() {
        EnumMap<Tier, Integer> counts = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            counts.put(tier, 0);
        }
        for (Tenant tenant : tenantsById.values()) {
            counts.merge(tenant.tier(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    public int size() {
        return tenantsById.size();
    }
}
