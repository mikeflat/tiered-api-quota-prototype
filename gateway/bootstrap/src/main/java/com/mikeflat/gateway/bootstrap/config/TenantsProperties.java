package com.mikeflat.gateway.bootstrap.config;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tenants")
public record TenantsProperties(@NotNull Path file) {
}
