package com.mikeflat.gateway.bootstrap;

import com.mikeflat.gateway.app.usage.UsageCounter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class ObservedUsageCounterConfiguration {

    @Bean
    static BeanPostProcessor observeUsageCounter() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof UsageCounter counter && !(bean instanceof ObservedUsageCounter)) {
                    return new ObservedUsageCounter(counter);
                }
                return bean;
            }
        };
    }
}
