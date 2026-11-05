package com.mikeflat.gateway.bootstrap.config;

import org.springframework.context.annotation.Configuration;

/**
 * Wires the outbound adapter module ({@code :adapters:infra}).
 *
 * <p>Port implementations, such as Redis stores and config loaders, go here. Declare each one as
 * a {@code @Bean} method here, rather than annotating classes in that module, so the module stays
 * free of Spring. Redis clients come from Boot's auto-configuration ({@code spring.data.redis.*}).
 */
@Configuration(proxyBeanMethods = false)
public class InfraConfiguration {
}
