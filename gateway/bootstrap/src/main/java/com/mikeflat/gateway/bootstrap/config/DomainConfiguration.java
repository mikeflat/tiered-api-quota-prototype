package com.mikeflat.gateway.bootstrap.config;

import org.springframework.context.annotation.Configuration;

/**
 * Wires the domain module ({@code :domain}).
 *
 * <p>Domain services and policies that need to be shared as beans go here. Declare each one as a
 * {@code @Bean} method here, rather than annotating classes in that module, so the module stays
 * free of Spring.
 */
@Configuration(proxyBeanMethods = false)
public class DomainConfiguration {
}
