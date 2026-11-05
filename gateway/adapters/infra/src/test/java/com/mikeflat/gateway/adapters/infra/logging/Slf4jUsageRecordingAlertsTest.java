package com.mikeflat.gateway.adapters.infra.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mikeflat.gateway.app.usage.UsageRecordingFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;

class Slf4jUsageRecordingAlertsTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(Slf4jUsageRecordingAlerts.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Slf4jUsageRecordingAlerts alerts = new Slf4jUsageRecordingAlerts();

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
    }

    @Test
    void outage_is_one_warning_with_cause_and_consequences() {
        alerts.recordingFailing(new UsageRecordingFailedException(
                "recording usage of tenant 7 in period 30000000 failed",
                new RedisConnectionFailureException("Unable to connect to Redis")));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent warning = appender.list.getFirst();
        assertThat(warning.getLevel()).isEqualTo(Level.WARN);
        assertThat(warning.getFormattedMessage())
                .isEqualTo("Redis unavailable, usage recording failing: no new tenants are blocked"
                        + " and counting is paused (Unable to connect to Redis)");
        assertThat(warning.getThrowableProxy()).isNull();
    }

    @Test
    void resume_is_one_info_line() {
        alerts.recordingResumed();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent info = appender.list.getFirst();
        assertThat(info.getLevel()).isEqualTo(Level.INFO);
        assertThat(info.getFormattedMessage()).isEqualTo("Redis available again, usage recording resumed");
        assertThat(info.getThrowableProxy()).isNull();
    }
}
