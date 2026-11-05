package com.mikeflat.gateway.domain.tenant;

public record Tenant(long id, String name, Tier tier) {

    public Tenant {
        if (id < 1) {
            throw new IllegalArgumentException("tenant id must be a positive integer, got " + id);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tenant name must not be blank (id " + id + ")");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tenant tier must not be null (id " + id + ")");
        }
    }

    public Tenant withTier(Tier newTier) {
        return new Tenant(id, name, newTier);
    }
}
