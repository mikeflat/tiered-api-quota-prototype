package com.mikeflat.gateway.app.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.app.metrics.LatestTrafficMetrics;
import com.mikeflat.gateway.domain.quota.PeriodTotals;
import com.mikeflat.gateway.domain.quota.QuotaPeriod;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.TenantFilter;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildAdminSnapshotTest {

    private static final QuotaSettings SETTINGS = new QuotaSettings(
            Duration.ofSeconds(60), Map.of(Tier.BRONZE, 100L, Tier.SILVER, 1_000L, Tier.GOLD, 10_000L), 80);
    private static final Instant NOW = Instant.ofEpochSecond(6_000_045L);
    private static final long PERIOD = 100_000L;
    private static final List<Tenant> SEED = List.of(
            new Tenant(1, "Acme Corp", Tier.BRONZE),
            new Tenant(2, "Bluebird Analytics", Tier.BRONZE),
            new Tenant(3, "Cobalt Labs", Tier.BRONZE),
            new Tenant(4, "Driftwood Travel", Tier.BRONZE),
            new Tenant(5, "Ember Foods", Tier.BRONZE),
            new Tenant(6, "Fjord Logistics", Tier.BRONZE),
            new Tenant(7, "Granite Insurance", Tier.BRONZE),
            new Tenant(8, "Harbor Health", Tier.BRONZE),
            new Tenant(9, "Ironwood Retail", Tier.SILVER),
            new Tenant(10, "Juniper Media", Tier.SILVER),
            new Tenant(11, "Kestrel Payments", Tier.SILVER),
            new Tenant(12, "Lumen Energy", Tier.SILVER),
            new Tenant(13, "Meridian Bank", Tier.SILVER),
            new Tenant(14, "Nimbus Cloud", Tier.GOLD),
            new Tenant(15, "Orbit Telecom", Tier.GOLD));

    private final TenantRegistry registry = new TenantRegistry(SEED);
    private final LatestTrafficMetrics latestTrafficMetrics = new LatestTrafficMetrics();
    private final FakeUsageReader usageReader = new FakeUsageReader();
    private final BuildAdminSnapshot buildAdminSnapshot = new BuildAdminSnapshot(
            registry, SETTINGS, latestTrafficMetrics, usageReader, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void first_page_sorted_by_name_with_total() {
        TenantRows.Available rows = available(buildAdminSnapshot.build(SnapshotQuery.firstPage()));

        assertThat(rows.total()).isEqualTo(15);
        assertThat(rows.page()).isEqualTo(new PageRequest(0, 10));
        assertThat(rows.rows()).extracting(row -> row.tenant().id())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void page_past_end_is_empty_without_usage_read() {
        TenantRows.Available rows = available(build(TenantFilter.none(), Optional.empty(), new PageRequest(5, 10)));

        assertThat(rows.rows()).isEmpty();
        assertThat(rows.total()).isEqualTo(15);
        assertThat(rows.page().page()).isEqualTo(5);
        assertThat(usageReader.usageCalls).isEmpty();
    }

    @Test
    void usage_read_once_for_page_tenants_only() {
        usageReader.usage.put(12L, new TenantUsage(40, 3));

        TenantRows.Available rows = available(build(TenantFilter.none(), Optional.empty(), new PageRequest(1, 3)));

        assertThat(usageReader.usageCalls).containsExactly(List.of(4L, 5L, 6L));
        assertThat(rows.rows()).allSatisfy(row -> assertThat(row.usage()).contains(new RowUsage(0, 0, TenantStatus.OK)));
        assertThat(usageReader.usagePeriods).containsOnly(PERIOD);
    }

    @Test
    void row_status_uses_current_limit() {
        usageReader.usage.put(1L, new TenantUsage(100, 2));
        usageReader.usage.put(2L, new TenantUsage(85, 0));
        registry.changeTier(1, Tier.SILVER);

        List<TenantRow> rows = available(build(TenantFilter.none().withNameContaining("a"), Optional.empty(),
                new PageRequest(0, 2))).rows();

        assertThat(rows).containsExactly(
                new TenantRow(new Tenant(1, "Acme Corp", Tier.SILVER), 1_000,
                        Optional.of(new RowUsage(100, 2, TenantStatus.OK))),
                new TenantRow(new Tenant(2, "Bluebird Analytics", Tier.BRONZE), 100,
                        Optional.of(new RowUsage(85, 0, TenantStatus.NEAR))));
    }

    @Test
    void near_filter_keeps_near_members() {
        usageReader.near.addAll(Set.of(3L, 13L));
        usageReader.over.add(1L);

        TenantRows.Available rows = available(build(TenantFilter.none(), Optional.of(TenantStatus.NEAR),
                PageRequest.defaults()));

        assertThat(rows.rows()).extracting(row -> row.tenant().id()).containsExactly(3L, 13L);
        assertThat(rows.total()).isEqualTo(2);
        assertThat(usageReader.flaggedCalls).containsExactly(Set.of(TenantStatus.NEAR));
    }

    @Test
    void over_filter_combines_with_tier() {
        usageReader.over.addAll(Set.of(1L, 9L));

        TenantRows.Available rows = available(build(TenantFilter.none().withTier(Tier.BRONZE),
                Optional.of(TenantStatus.OVER), PageRequest.defaults()));

        assertThat(rows.rows()).extracting(row -> row.tenant().id()).containsExactly(1L);
        assertThat(usageReader.flaggedCalls).containsExactly(Set.of(TenantStatus.OVER));
    }

    @Test
    void ok_filter_excludes_near_and_over() {
        usageReader.near.addAll(Set.of(3L, 13L));
        usageReader.over.add(1L);

        TenantRows.Available rows = available(build(TenantFilter.none(), Optional.of(TenantStatus.OK),
                new PageRequest(0, 100)));

        assertThat(rows.total()).isEqualTo(12);
        assertThat(rows.rows()).extracting(row -> row.tenant().id()).doesNotContain(1L, 3L, 13L);
        assertThat(usageReader.flaggedCalls).containsExactly(Set.of(TenantStatus.NEAR, TenantStatus.OVER));
    }

    @Test
    void usage_failure_without_status_keeps_rows_without_usage() {
        usageReader.down = true;

        TenantRows.Available rows = available(build(TenantFilter.none().withTier(Tier.GOLD), Optional.empty(),
                PageRequest.defaults()));

        assertThat(rows.total()).isEqualTo(2);
        assertThat(rows.rows()).containsExactly(
                new TenantRow(new Tenant(14, "Nimbus Cloud", Tier.GOLD), 10_000, Optional.empty()),
                new TenantRow(new Tenant(15, "Orbit Telecom", Tier.GOLD), 10_000, Optional.empty()));
    }

    @Test
    void status_filter_failure_marks_rows_unavailable() {
        usageReader.down = true;

        AdminSnapshot snapshot = build(TenantFilter.none(), Optional.of(TenantStatus.OVER), new PageRequest(2, 5));

        assertThat(snapshot.tenants()).isEqualTo(new TenantRows.Unavailable(new PageRequest(2, 5)));
    }

    @Test
    void stale_metrics_are_reported_absent() {
        TrafficMetrics metrics = new TrafficMetrics(List.of(), new PeriodTotals(1, 0), 0, 0);
        latestTrafficMetrics.store(metrics, NOW.minusSeconds(4));

        assertThat(buildAdminSnapshot.build(SnapshotQuery.firstPage()).metrics()).isEmpty();

        latestTrafficMetrics.store(metrics, NOW.minusSeconds(1));

        assertThat(buildAdminSnapshot.build(SnapshotQuery.firstPage()).metrics()).contains(metrics);
    }

    @Test
    void period_comes_from_clock() {
        assertThat(buildAdminSnapshot.build(SnapshotQuery.firstPage()).period())
                .isEqualTo(new QuotaPeriod(PERIOD, Instant.ofEpochSecond(6_000_060L), 15));
    }

    private AdminSnapshot build(TenantFilter filter, Optional<TenantStatus> status, PageRequest page) {
        return buildAdminSnapshot.build(new SnapshotQuery(filter, status, page));
    }

    private static TenantRows.Available available(AdminSnapshot snapshot) {
        assertThat(snapshot.tenants()).isInstanceOf(TenantRows.Available.class);
        return (TenantRows.Available) snapshot.tenants();
    }

    private static final class FakeUsageReader implements TenantUsageReader {
        final Map<Long, TenantUsage> usage = new HashMap<>();
        final Set<Long> near = new HashSet<>();
        final Set<Long> over = new HashSet<>();
        final List<List<Long>> usageCalls = new ArrayList<>();
        final List<Long> usagePeriods = new ArrayList<>();
        final List<Set<TenantStatus>> flaggedCalls = new ArrayList<>();
        boolean down;

        @Override
        public Map<Long, TenantUsage> usageOf(long periodIndex, List<Long> tenantIds) {
            usageCalls.add(List.copyOf(tenantIds));
            usagePeriods.add(periodIndex);
            failIfDown();
            Map<Long, TenantUsage> result = new HashMap<>();
            tenantIds.forEach(id -> result.put(id, usage.getOrDefault(id, TenantUsage.NONE)));
            return result;
        }

        @Override
        public Set<Long> flaggedTenants(long periodIndex, Set<TenantStatus> statuses) {
            flaggedCalls.add(Set.copyOf(statuses));
            failIfDown();
            Set<Long> members = new HashSet<>();
            if (statuses.contains(TenantStatus.NEAR)) {
                members.addAll(near);
            }
            if (statuses.contains(TenantStatus.OVER)) {
                members.addAll(over);
            }
            return members;
        }

        private void failIfDown() {
            if (down) {
                throw new UsageReadFailedException("redis down", new IllegalStateException());
            }
        }
    }
}
