package com.mikeflat.gateway.adapters.api.web;

import com.mikeflat.gateway.adapters.api.generated.api.AdminApi;
import com.mikeflat.gateway.adapters.api.generated.model.AdminSnapshot;
import com.mikeflat.gateway.adapters.api.generated.model.TenantTier;
import com.mikeflat.gateway.adapters.api.generated.model.TierChangeRequest;
import com.mikeflat.gateway.adapters.api.generated.model.TierLimit;
import com.mikeflat.gateway.app.admin.ChangeTenantTier;
import com.mikeflat.gateway.app.admin.ListTiers;
import com.mikeflat.gateway.app.admin.TierChange;
import com.mikeflat.gateway.app.snapshot.BuildAdminSnapshot;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AdminController implements AdminApi {

    static final String TENANT_NOT_FOUND = "No tenant has this id";

    private final ListTiers listTiers;
    private final ChangeTenantTier changeTenantTier;
    private final BuildAdminSnapshot buildAdminSnapshot;

    public AdminController(
            ListTiers listTiers, ChangeTenantTier changeTenantTier, BuildAdminSnapshot buildAdminSnapshot) {
        this.listTiers = listTiers;
        this.changeTenantTier = changeTenantTier;
        this.buildAdminSnapshot = buildAdminSnapshot;
    }

    @Override
    public ResponseEntity<List<TierLimit>> listTiers() {
        return ResponseEntity.ok(AdminDtoMapper.tierLimits(listTiers.list()));
    }

    @Override
    public ResponseEntity<TenantTier> changeTenantTier(Long id, TierChangeRequest request) {
        long tenantId = AdminParameters.tenantId(id);
        Tier tier = AdminParameters.requiredTier(request.getTier());
        return switch (changeTenantTier.change(tenantId, tier)) {
            case TierChange.Changed changed -> ResponseEntity.ok(AdminDtoMapper.tenantTier(changed));
            case TierChange.TenantNotFound notFound ->
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, TENANT_NOT_FOUND);
        };
    }

    @Override
    public ResponseEntity<AdminSnapshot> getSnapshot(
            String name, Long id, String tier, String status, Integer page, Integer size) {
        return ResponseEntity.ok(AdminDtoMapper.snapshot(
                buildAdminSnapshot.build(AdminParameters.snapshotQuery(name, id, tier, status, page, size))));
    }
}
