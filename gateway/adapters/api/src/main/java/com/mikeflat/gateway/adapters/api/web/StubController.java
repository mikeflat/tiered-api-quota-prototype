package com.mikeflat.gateway.adapters.api.web;

import com.mikeflat.gateway.adapters.api.generated.api.ResourceApi;
import com.mikeflat.gateway.adapters.api.generated.model.MockResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StubController implements ResourceApi {

    static final String RESOURCE_ID = "mock-resource";
    static final String RESOURCE_MESSAGE = "Served by the quota gateway stub";

    @Override
    public ResponseEntity<MockResource> getResource(Long tenantIdCheckedByQuotaFilter) {
        return ResponseEntity.ok(new MockResource(RESOURCE_ID, RESOURCE_MESSAGE));
    }
}
