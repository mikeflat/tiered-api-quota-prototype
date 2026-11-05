package com.mikeflat.gateway.domain.tenant;

import java.util.List;

public interface TenantRepository {

    List<Tenant> findAll();

    String description();
}
