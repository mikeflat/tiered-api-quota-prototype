package com.mikeflat.gateway.bootstrap.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("usage-recording")
public record UsageRecordingProperties(@NotNull @Min(1) Integer queueCapacity) {
}
