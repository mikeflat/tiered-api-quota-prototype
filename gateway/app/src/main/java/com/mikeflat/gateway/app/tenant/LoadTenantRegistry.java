package com.mikeflat.gateway.app.tenant;

import com.mikeflat.gateway.domain.tenant.TenantLoadException;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.TenantRepository;

public final class LoadTenantRegistry {

    private final TenantRepository repository;

    public LoadTenantRegistry(TenantRepository repository) {
        this.repository = repository;
    }

    public TenantRegistry load() {
        var tenants = repository.findAll();
        try {
            return new TenantRegistry(tenants);
        } catch (IllegalArgumentException e) {
            throw new TenantLoadException(repository.description(), e.getMessage(), e);
        }
    }

    public String sourceDescription() {
        return repository.description();
    }
}
