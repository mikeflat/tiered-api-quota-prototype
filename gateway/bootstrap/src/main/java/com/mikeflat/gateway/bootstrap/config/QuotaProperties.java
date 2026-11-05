package com.mikeflat.gateway.bootstrap.config;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("quota")
public record QuotaProperties(
        @NotNull @Min(1) Long periodSeconds,
        @NotNull @Valid Limits limits,
        @NotNull @Min(1) @Max(99) Integer nearThresholdPercent) {

    public record Limits(
            @NotNull @Min(1) Long bronze,
            @NotNull @Min(1) Long silver,
            @NotNull @Min(1) Long gold) {
    }

    public QuotaSettings toSettings() {
        return new QuotaSettings(
                Duration.ofSeconds(periodSeconds),
                Map.of(Tier.BRONZE, limits.bronze(), Tier.SILVER, limits.silver(), Tier.GOLD, limits.gold()),
                nearThresholdPercent);
    }
}
