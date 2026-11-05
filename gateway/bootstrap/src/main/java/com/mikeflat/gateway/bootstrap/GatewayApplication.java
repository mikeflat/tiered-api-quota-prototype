package com.mikeflat.gateway.bootstrap;

import com.mikeflat.gateway.bootstrap.config.ApiConfiguration;
import com.mikeflat.gateway.bootstrap.config.AppConfiguration;
import com.mikeflat.gateway.bootstrap.config.DomainConfiguration;
import com.mikeflat.gateway.bootstrap.config.InfraConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Entry point of the quota gateway service.
 *
 * <p>Component scanning covers only this bootstrap package. Beans from the other modules are
 * declared explicitly in the imported per-module configuration classes, so the inner layers
 * never need Spring annotations.
 */
@SpringBootApplication(scanBasePackageClasses = GatewayApplication.class)
@Import({
    DomainConfiguration.class,
    AppConfiguration.class,
    ApiConfiguration.class,
    InfraConfiguration.class,
})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
