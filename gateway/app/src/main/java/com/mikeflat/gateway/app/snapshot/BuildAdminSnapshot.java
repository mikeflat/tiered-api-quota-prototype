package com.mikeflat.gateway.app.snapshot;

import com.mikeflat.gateway.app.metrics.LatestTrafficMetrics;
import com.mikeflat.gateway.domain.quota.QuotaPeriod;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BuildAdminSnapshot {

    private final TenantRegistry registry;
    private final QuotaSettings settings;
    private final LatestTrafficMetrics latestTrafficMetrics;
    private final TenantUsageReader usageReader;
    private final Clock clock;

    public BuildAdminSnapshot(
            TenantRegistry registry,
            QuotaSettings settings,
            LatestTrafficMetrics latestTrafficMetrics,
            TenantUsageReader usageReader,
            Clock clock) {
        this.registry = registry;
        this.settings = settings;
        this.latestTrafficMetrics = latestTrafficMetrics;
        this.usageReader = usageReader;
        this.clock = clock;
    }

    public AdminSnapshot build(SnapshotQuery query) {
        Instant now = clock.instant();
        QuotaPeriod period = settings.periodAt(now);
        return new AdminSnapshot(period, latestTrafficMetrics.current(now), rows(query, period.index()));
    }

    private TenantRows rows(SnapshotQuery query, long periodIndex) {
        List<Tenant> candidates = registry.search(query.filter());
        if (query.status().isEmpty()) {
            return pageOf(candidates, query.page(), periodIndex, false);
        }
        try {
            List<Tenant> withStatus = withStatus(candidates, query.status().get(), periodIndex);
            return pageOf(withStatus, query.page(), periodIndex, true);
        } catch (UsageReadFailedException redisUnavailable) {
            return new TenantRows.Unavailable(query.page());
        }
    }

    private List<Tenant> withStatus(List<Tenant> candidates, TenantStatus status, long periodIndex) {
        Set<Long> flagged = usageReader.flaggedTenants(periodIndex, setsHolding(status));
        boolean keepMembers = status != TenantStatus.OK;
        return candidates.stream()
                .filter(tenant -> flagged.contains(tenant.id()) == keepMembers)
                .toList();
    }

    private static Set<TenantStatus> setsHolding(TenantStatus status) {
        return switch (status) {
            case NEAR -> EnumSet.of(TenantStatus.NEAR);
            case OVER -> EnumSet.of(TenantStatus.OVER);
            case OK -> EnumSet.of(TenantStatus.NEAR, TenantStatus.OVER);
        };
    }

    private TenantRows pageOf(List<Tenant> matching, PageRequest page, long periodIndex, boolean usageRequired) {
        List<Tenant> onPage = cut(matching, page);
        if (onPage.isEmpty()) {
            return new TenantRows.Available(List.of(), matching.size(), page);
        }
        Optional<Map<Long, TenantUsage>> usage = usageOf(onPage, periodIndex);
        if (usage.isEmpty() && usageRequired) {
            return new TenantRows.Unavailable(page);
        }
        List<TenantRow> rows = onPage.stream().map(tenant -> row(tenant, usage)).toList();
        return new TenantRows.Available(rows, matching.size(), page);
    }

    private static List<Tenant> cut(List<Tenant> matching, PageRequest page) {
        if (page.offset() >= matching.size()) {
            return List.of();
        }
        int from = (int) page.offset();
        return matching.subList(from, Math.min(from + page.size(), matching.size()));
    }

    private Optional<Map<Long, TenantUsage>> usageOf(List<Tenant> onPage, long periodIndex) {
        try {
            return Optional.of(usageReader.usageOf(periodIndex, onPage.stream().map(Tenant::id).toList()));
        } catch (UsageReadFailedException redisUnavailable) {
            return Optional.empty();
        }
    }

    private TenantRow row(Tenant tenant, Optional<Map<Long, TenantUsage>> usage) {
        long limit = settings.limitOf(tenant.tier());
        Optional<RowUsage> rowUsage = usage.map(byId -> rowUsage(tenant, byId.getOrDefault(tenant.id(), TenantUsage.NONE)));
        return new TenantRow(tenant, limit, rowUsage);
    }

    private RowUsage rowUsage(Tenant tenant, TenantUsage usage) {
        TenantStatus status = TenantStatus.of(usage.used(), settings.limitsOf(tenant.tier()));
        return new RowUsage(usage.used(), usage.rejected(), status);
    }
}
