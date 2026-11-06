package com.mikeflat.gateway.bootstrap.config;

import com.mikeflat.gateway.adapters.api.filter.QuotaFilter;
import com.mikeflat.gateway.adapters.api.web.AdminController;
import com.mikeflat.gateway.adapters.api.web.ConstraintViolationProblemAdvice;
import com.mikeflat.gateway.adapters.api.web.StubController;
import com.mikeflat.gateway.app.admin.ChangeTenantTier;
import com.mikeflat.gateway.app.admin.ListTiers;
import com.mikeflat.gateway.app.quota.AdmitBusinessCall;
import com.mikeflat.gateway.app.snapshot.BuildAdminSnapshot;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

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

    private static final String BUSINESS_PATHS = "/api/*";

    @Bean
    StubController stubController() {
        return new StubController();
    }

    @Bean
    AdminController adminController(
            ListTiers listTiers, ChangeTenantTier changeTenantTier, BuildAdminSnapshot buildAdminSnapshot) {
        return new AdminController(listTiers, changeTenantTier, buildAdminSnapshot);
    }

    @Bean
    ConstraintViolationProblemAdvice constraintViolationProblemAdvice() {
        return new ConstraintViolationProblemAdvice();
    }

    @Bean
    FilterRegistrationBean<QuotaFilter> quotaFilterRegistration(
            AdmitBusinessCall admitBusinessCall, JsonMapper jsonMapper) {
        FilterRegistrationBean<QuotaFilter> registration =
                new FilterRegistrationBean<>(new QuotaFilter(admitBusinessCall, jsonMapper));
        registration.addUrlPatterns(BUSINESS_PATHS);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
