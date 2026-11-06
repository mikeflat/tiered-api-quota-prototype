package com.mikeflat.gateway.adapters.api.web;

import com.mikeflat.gateway.adapters.api.generated.model.TenantPage;
import com.mikeflat.gateway.adapters.api.generated.model.TenantTier;
import com.mikeflat.gateway.app.admin.TierChange;
import com.mikeflat.gateway.app.admin.TierLimit;
import com.mikeflat.gateway.app.snapshot.AdminSnapshot;
import com.mikeflat.gateway.app.snapshot.RowUsage;
import com.mikeflat.gateway.app.snapshot.TenantRow;
import com.mikeflat.gateway.app.snapshot.TenantRows;
import com.mikeflat.gateway.domain.quota.PeriodTotals;
import com.mikeflat.gateway.domain.quota.QuotaPeriod;
import com.mikeflat.gateway.domain.quota.SecondTraffic;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import com.mikeflat.gateway.domain.tenant.Tenant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class AdminDtoMapper {

    private AdminDtoMapper() {
    }

    static List<com.mikeflat.gateway.adapters.api.generated.model.TierLimit> tierLimits(List<TierLimit> tiers) {
        return tiers.stream()
                .map(tier -> new com.mikeflat.gateway.adapters.api.generated.model.TierLimit(
                        tier.tier().displayName(), tier.limit()))
                .toList();
    }

    static TenantTier tenantTier(TierChange.Changed changed) {
        Tenant tenant = changed.tenant();
        return new TenantTier(tenant.id(), tenant.name(), tenant.tier().displayName(), changed.limit());
    }

    static com.mikeflat.gateway.adapters.api.generated.model.AdminSnapshot snapshot(AdminSnapshot snapshot) {
        return new com.mikeflat.gateway.adapters.api.generated.model.AdminSnapshot(
                period(snapshot.period()),
                snapshot.metrics().map(AdminDtoMapper::metrics).orElse(null),
                tenantPage(snapshot.tenants()));
    }

    private static com.mikeflat.gateway.adapters.api.generated.model.QuotaPeriod period(QuotaPeriod period) {
        return new com.mikeflat.gateway.adapters.api.generated.model.QuotaPeriod(
                period.index(), period.endsAt().atOffset(ZoneOffset.UTC), period.secondsLeft());
    }

    private static com.mikeflat.gateway.adapters.api.generated.model.TrafficMetrics metrics(TrafficMetrics metrics) {
        return new com.mikeflat.gateway.adapters.api.generated.model.TrafficMetrics(
                metrics.perSecond().stream().map(AdminDtoMapper::second).toList(),
                totals(metrics.totals()),
                metrics.tenantsNear(),
                metrics.tenantsOver());
    }

    private static com.mikeflat.gateway.adapters.api.generated.model.SecondTraffic second(SecondTraffic second) {
        return new com.mikeflat.gateway.adapters.api.generated.model.SecondTraffic(
                second.second(), second.allowed(), second.rejected());
    }

    private static com.mikeflat.gateway.adapters.api.generated.model.PeriodTotals totals(PeriodTotals totals) {
        return new com.mikeflat.gateway.adapters.api.generated.model.PeriodTotals(totals.allowed(), totals.rejected());
    }

    private static TenantPage tenantPage(TenantRows tenants) {
        return switch (tenants) {
            case TenantRows.Available available -> new TenantPage(
                    true,
                    available.rows().stream().map(AdminDtoMapper::row).toList(),
                    available.total(),
                    available.page().page(),
                    available.page().size());
            case TenantRows.Unavailable unavailable -> new TenantPage(
                    false, null, null, unavailable.page().page(), unavailable.page().size());
        };
    }

    private static com.mikeflat.gateway.adapters.api.generated.model.TenantRow row(TenantRow row) {
        Tenant tenant = row.tenant();
        Optional<RowUsage> usage = row.usage();
        return new com.mikeflat.gateway.adapters.api.generated.model.TenantRow(
                tenant.id(),
                tenant.name(),
                tenant.tier().displayName(),
                row.limit(),
                usage.map(RowUsage::used).orElse(null),
                usage.map(RowUsage::rejected).orElse(null),
                usage.map(RowUsage::status).map(AdminDtoMapper::statusName).orElse(null));
    }

    static String statusName(TenantStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
