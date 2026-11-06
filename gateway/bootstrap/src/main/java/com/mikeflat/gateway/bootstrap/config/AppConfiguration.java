package com.mikeflat.gateway.bootstrap.config;

import com.mikeflat.gateway.app.quota.AdmitBusinessCall;
import com.mikeflat.gateway.app.quota.BlockTenantsOverLimit;
import com.mikeflat.gateway.app.tenant.LoadTenantRegistry;
import com.mikeflat.gateway.app.usage.TenantUsageLimits;
import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecorder;
import com.mikeflat.gateway.app.usage.UsageRecordingAlerts;
import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import com.mikeflat.gateway.domain.tenant.TenantRepository;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application module ({@code :app}).
 *
 * <p>Use-case services go here, built from the ports they depend on. Declare each one as a
 * {@code @Bean} method here, rather than annotating classes in that module, so the module stays
 * free of Spring.
 */
@Configuration(proxyBeanMethods = false)
public class AppConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AppConfiguration.class);

    @Bean
    LoadTenantRegistry loadTenantRegistry(TenantRepository tenantRepository) {
        return new LoadTenantRegistry(tenantRepository);
    }

    @Bean
    TenantRegistry tenantRegistry(LoadTenantRegistry loadTenantRegistry) {
        TenantRegistry registry = loadTenantRegistry.load();
        Map<Tier, Integer> counts = registry.countByTier();
        log.info("Loaded {} tenants (Bronze {}, Silver {}, Gold {}) from {}",
                registry.size(),
                counts.get(Tier.BRONZE),
                counts.get(Tier.SILVER),
                counts.get(Tier.GOLD),
                loadTenantRegistry.sourceDescription());
        return registry;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BlockTenantsOverLimit blockTenantsOverLimit(QuotaSettings quotaSettings, BlockedTenants blockedTenants, Clock clock) {
        return new BlockTenantsOverLimit(quotaSettings, blockedTenants, clock);
    }

    @Bean
    TenantUsageLimits tenantUsageLimits(TenantRegistry tenantRegistry, QuotaSettings quotaSettings) {
        return new TenantUsageLimits(tenantRegistry, quotaSettings);
    }

    @Bean
    UsageRecorder usageRecorder(
            UsageCounter usageCounter,
            TenantUsageLimits tenantUsageLimits,
            Executor usageWriterExecutor,
            BlockTenantsOverLimit blockTenantsOverLimit,
            UsageRecordingAlerts usageRecordingAlerts) {
        return new UsageRecorder(
                usageCounter, tenantUsageLimits, usageWriterExecutor, blockTenantsOverLimit, usageRecordingAlerts);
    }

    @Bean
    AdmitBusinessCall admitBusinessCall(
            AdmissionPolicy admissionPolicy, UsageRecorder usageRecorder, QuotaSettings quotaSettings, Clock clock) {
        return new AdmitBusinessCall(admissionPolicy, usageRecorder, quotaSettings, clock);
    }
}
