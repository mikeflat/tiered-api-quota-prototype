package com.mikeflat.gateway.bootstrap.config;

import com.mikeflat.gateway.adapters.infra.logging.Slf4jUsageRecordingAlerts;
import com.mikeflat.gateway.adapters.infra.redis.RedisUsageCounter;
import com.mikeflat.gateway.app.usage.UsageCounter;
import com.mikeflat.gateway.app.usage.UsageRecordingAlerts;
import com.mikeflat.gateway.domain.quota.QuotaSettings;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Wires the outbound adapter module ({@code :adapters:infra}).
 *
 * <p>Port implementations, such as Redis stores and config loaders, go here. Declare each one as
 * a {@code @Bean} method here, rather than annotating classes in that module, so the module stays
 * free of Spring. Redis clients come from Boot's auto-configuration ({@code spring.data.redis.*}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageRecordingProperties.class)
public class InfraConfiguration {

    @Bean
    RedisScript<Long> recordUsageScript() {
        return RedisScript.of(new ClassPathResource(RedisUsageCounter.RECORD_USAGE_SCRIPT), Long.class);
    }

    @Bean
    UsageCounter usageCounter(
            StringRedisTemplate redisTemplate, RedisScript<Long> recordUsageScript, QuotaSettings quotaSettings) {
        long twoPeriodsSeconds = Math.multiplyExact(2, quotaSettings.periodSeconds());
        return new RedisUsageCounter(redisTemplate, recordUsageScript, twoPeriodsSeconds);
    }

    @Bean
    UsageRecordingAlerts usageRecordingAlerts() {
        return new Slf4jUsageRecordingAlerts();
    }

    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor usageWriterExecutor(UsageRecordingProperties properties) {
        return UsageWriterExecutor.create(properties.queueCapacity());
    }
}
