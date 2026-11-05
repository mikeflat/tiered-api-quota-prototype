package com.mikeflat.gateway.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mikeflat.gateway.domain.quota.QuotaSettings;
import com.mikeflat.gateway.domain.tenant.Tier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class QuotaPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(DomainConfiguration.class);

    @Test
    void defaults() {
        runner.run(context -> {
            QuotaSettings settings = context.getBean(QuotaSettings.class);

            assertThat(settings.period()).isEqualTo(Duration.ofSeconds(60));
            assertThat(settings.limitOf(Tier.BRONZE)).isEqualTo(100);
            assertThat(settings.limitOf(Tier.SILVER)).isEqualTo(1_000);
            assertThat(settings.limitOf(Tier.GOLD)).isEqualTo(10_000);
            assertThat(settings.nearThresholdPercent()).isEqualTo(80);
        });
    }

    @Test
    void period_override() {
        runner.withPropertyValues("QUOTA_PERIOD_SECONDS=86400").run(context -> {
            QuotaSettings settings = context.getBean(QuotaSettings.class);

            assertThat(settings.periodSeconds()).isEqualTo(86_400);
            assertThat(settings.limitOf(Tier.BRONZE)).isEqualTo(100);
            assertThat(settings.nearThresholdPercent()).isEqualTo(80);
        });
    }

    @Test
    void limit_and_threshold_override() {
        runner.withPropertyValues("QUOTA_LIMIT_GOLD=500", "QUOTA_NEAR_THRESHOLD_PERCENT=90").run(context -> {
            QuotaSettings settings = context.getBean(QuotaSettings.class);

            assertThat(settings.limitOf(Tier.GOLD)).isEqualTo(500);
            assertThat(settings.nearThresholdPercent()).isEqualTo(90);
            assertThat(settings.limitOf(Tier.BRONZE)).isEqualTo(100);
            assertThat(settings.limitOf(Tier.SILVER)).isEqualTo(1_000);
            assertThat(settings.periodSeconds()).isEqualTo(60);
        });
    }

    @ParameterizedTest
    @CsvSource({
        "QUOTA_PERIOD_SECONDS, 0, periodSeconds",
        "QUOTA_LIMIT_SILVER, -5, limits.silver",
        "QUOTA_NEAR_THRESHOLD_PERCENT, 100, nearThresholdPercent",
        "QUOTA_NEAR_THRESHOLD_PERCENT, 0, nearThresholdPercent",
    })
    void invalid_value_fails_naming_property_and_value(String variable, String value, String field) {
        runner.withPropertyValues(variable + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureText(context))
                    .contains("quota")
                    .contains("'" + field + "'")
                    .contains("rejected value [" + value + "]");
        });
    }

    @Test
    void not_a_number_fails_naming_property_and_value() {
        runner.withPropertyValues("QUOTA_LIMIT_BRONZE=lots").run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureText(context))
                    .contains("quota.limits.bronze")
                    .contains("lots");
        });
    }

    private static String failureText(AssertableApplicationContext context) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return String.join("\n", messages);
    }
}
