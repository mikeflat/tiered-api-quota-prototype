package com.mikeflat.gateway.bootstrap.config;

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
}
