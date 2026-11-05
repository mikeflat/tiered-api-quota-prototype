package com.mikeflat.gateway.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class UsageRecordingPropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UsageRecordingProperties.class)
    static class UsageRecordingPropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(UsageRecordingPropertiesOnly.class);

    @Test
    void queue_capacity_defaults_to_ten_thousand() {
        runner.run(context -> assertThat(context.getBean(UsageRecordingProperties.class).queueCapacity())
                .isEqualTo(10_000));
    }

    @Test
    void queue_capacity_from_environment() {
        runner.withPropertyValues("USAGE_QUEUE_CAPACITY=2").run(context ->
                assertThat(context.getBean(UsageRecordingProperties.class).queueCapacity()).isEqualTo(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void queue_capacity_below_one_fails_naming_property_and_value(String value) {
        runner.withPropertyValues("USAGE_QUEUE_CAPACITY=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureText(context))
                    .contains("usage-recording")
                    .contains("'queueCapacity'")
                    .contains("rejected value [" + value + "]");
        });
    }

    @Test
    void queue_capacity_that_is_not_a_number_fails_naming_property_and_value() {
        runner.withPropertyValues("USAGE_QUEUE_CAPACITY=many").run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureText(context))
                    .contains("usage-recording.queue-capacity")
                    .contains("many");
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
