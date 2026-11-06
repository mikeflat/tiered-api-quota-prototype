package com.mikeflat.gateway.adapters.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.adapters.api.generated.model.TenantPage;
import com.mikeflat.gateway.app.snapshot.AdminSnapshot;
import com.mikeflat.gateway.app.snapshot.PageRequest;
import com.mikeflat.gateway.app.snapshot.RowUsage;
import com.mikeflat.gateway.app.snapshot.TenantRow;
import com.mikeflat.gateway.app.snapshot.TenantRows;
import com.mikeflat.gateway.domain.quota.PeriodTotals;
import com.mikeflat.gateway.domain.quota.QuotaPeriod;
import com.mikeflat.gateway.domain.quota.SecondTraffic;
import com.mikeflat.gateway.domain.quota.TenantStatus;
import com.mikeflat.gateway.domain.quota.TrafficMetrics;
import com.mikeflat.gateway.domain.tenant.Tenant;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminDtoMapperTest {

    private static final QuotaPeriod PERIOD = new QuotaPeriod(100_000, Instant.ofEpochSecond(6_000_060L), 15);
    private static final Tenant ACME = new Tenant(1, "Acme Corp", Tier.BRONZE);

    @Test
    void unavailable_metrics_map_to_null() {
        var dto = AdminDtoMapper.snapshot(new AdminSnapshot(PERIOD, Optional.empty(), emptyPage()));

        assertThat(dto.getMetrics()).isNull();
        assertThat(dto.getPeriod().getIndex()).isEqualTo(100_000);
        assertThat(dto.getPeriod().getEndsAt()).isEqualTo(OffsetDateTime.of(1970, 3, 11, 10, 41, 0, 0, ZoneOffset.UTC));
        assertThat(dto.getPeriod().getSecondsLeft()).isEqualTo(15);
    }

    @Test
    void available_metrics_are_copied() {
        TrafficMetrics metrics = new TrafficMetrics(
                List.of(new SecondTraffic(6_000_044L, 3, 1)), new PeriodTotals(120, 7), 2, 1);

        var dto = AdminDtoMapper.snapshot(new AdminSnapshot(PERIOD, Optional.of(metrics), emptyPage())).getMetrics();

        assertThat(dto.getPerSecond()).singleElement().satisfies(second -> {
            assertThat(second.getSecond()).isEqualTo(6_000_044L);
            assertThat(second.getAllowed()).isEqualTo(3);
            assertThat(second.getRejected()).isEqualTo(1);
        });
        assertThat(dto.getTotals().getAllowed()).isEqualTo(120);
        assertThat(dto.getTotals().getRejected()).isEqualTo(7);
        assertThat(dto.getTenantsNear()).isEqualTo(2);
        assertThat(dto.getTenantsOver()).isEqualTo(1);
    }

    @Test
    void rows_without_usage_have_null_used_rejected_status() {
        TenantRows rows = new TenantRows.Available(
                List.of(new TenantRow(ACME, 100, Optional.empty())), 1, PageRequest.defaults());

        var row = AdminDtoMapper.snapshot(new AdminSnapshot(PERIOD, Optional.empty(), rows))
                .getTenants().getRows().getFirst();

        assertThat(row.getId()).isEqualTo(1);
        assertThat(row.getName()).isEqualTo("Acme Corp");
        assertThat(row.getTier()).isEqualTo("Bronze");
        assertThat(row.getLimit()).isEqualTo(100);
        assertThat(row.getUsed()).isNull();
        assertThat(row.getRejected()).isNull();
        assertThat(row.getStatus()).isNull();
    }

    @Test
    void rows_with_usage_carry_lower_case_status() {
        TenantRows rows = new TenantRows.Available(
                List.of(new TenantRow(ACME, 100, Optional.of(new RowUsage(85, 2, TenantStatus.NEAR)))),
                1,
                PageRequest.defaults());

        var row = AdminDtoMapper.snapshot(new AdminSnapshot(PERIOD, Optional.empty(), rows))
                .getTenants().getRows().getFirst();

        assertThat(row.getUsed()).isEqualTo(85);
        assertThat(row.getRejected()).isEqualTo(2);
        assertThat(row.getStatus()).isEqualTo("near");
    }

    @Test
    void unavailable_rows_map_to_null_rows_and_total() {
        TenantPage page = AdminDtoMapper.snapshot(
                new AdminSnapshot(PERIOD, Optional.empty(), new TenantRows.Unavailable(new PageRequest(2, 5))))
                .getTenants();

        assertThat(page.getRowsAvailable()).isFalse();
        assertThat(page.getRows()).isNull();
        assertThat(page.getTotal()).isNull();
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(5);
    }

    private static TenantRows emptyPage() {
        return new TenantRows.Available(List.of(), 0, PageRequest.defaults());
    }
}
