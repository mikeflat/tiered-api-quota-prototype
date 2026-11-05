package com.mikeflat.gateway.bootstrap.config;

import com.mikeflat.gateway.domain.quota.AdmissionPolicy;
import com.mikeflat.gateway.domain.quota.BlockedTenants;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the domain module ({@code :domain}).
 *
 * <p>Domain services and policies that need to be shared as beans go here. Declare each one as a
 * {@code @Bean} method here, rather than annotating classes in that module, so the module stays
 * free of Spring.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuotaProperties.class)
public class DomainConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DomainConfiguration.class);

    @Bean
    QuotaSettings quotaSettings(QuotaProperties properties) {
        QuotaSettings settings = properties.toSettings();
        log.info("Quota settings: period {} s, limits Bronze {}, Silver {}, Gold {}, near threshold {} %",
                settings.periodSeconds(),
                properties.limits().bronze(),
                properties.limits().silver(),
                properties.limits().gold(),
                settings.nearThresholdPercent());
        return settings;
    }

    @Bean
    BlockedTenants blockedTenants() {
        return new BlockedTenants();
    }

    @Bean
    AdmissionPolicy admissionPolicy(
            TenantRegistry tenantRegistry, BlockedTenants blockedTenants, QuotaSettings quotaSettings) {
        return new AdmissionPolicy(tenantRegistry, blockedTenants, quotaSettings);
    }
}
