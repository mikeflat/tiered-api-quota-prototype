package com.mikeflat.gateway.bootstrap.config;

import com.mikeflat.gateway.adapters.persistence.file.JsonFileTenantRepository;
import com.mikeflat.gateway.domain.tenant.TenantRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantsProperties.class)
public class PersistenceConfiguration {

    @Bean
    TenantRepository tenantRepository(TenantsProperties properties) {
        return new JsonFileTenantRepository(properties.file());
    }
}
