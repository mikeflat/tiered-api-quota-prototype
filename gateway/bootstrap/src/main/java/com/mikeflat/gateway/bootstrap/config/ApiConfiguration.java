package com.mikeflat.gateway.bootstrap.config;

import org.springframework.context.annotation.Configuration;

/**
 * Wires the inbound adapter module ({@code :adapters:api}).
 *
 * <p>REST controllers, request filters and other HTTP-related beans go here, each as a
 * {@code @Bean} method. Controllers and advice in that module carry {@code @RestController} /
 * {@code @RestControllerAdvice} only because Spring MVC needs the type-level annotation to map
 * them; they are not component-scanned.
 */
@Configuration(proxyBeanMethods = false)
public class ApiConfiguration {
}
