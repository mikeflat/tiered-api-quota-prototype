package com.mikeflat.gateway.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

@ExtendWith(OutputCaptureExtension.class)
class StartupFailureTest {

    @TempDir
    Path dir;

    private static final class WebServerStarts implements ApplicationListener<WebServerInitializedEvent> {
        final List<Integer> ports = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            ports.add(event.getWebServer().getPort());
        }
    }

    private static final String TENANTS_FILE_ARGUMENT_OUTRANKING_GRADLE_ENVIRONMENT = "--tenants.file=";

    private final WebServerStarts webServerStarts = new WebServerStarts();

    private Throwable startWithTenantsFile(Path file) {
        return start(TENANTS_FILE_ARGUMENT_OUTRANKING_GRADLE_ENVIRONMENT + file);
    }

    private Throwable start(String... args) {
        List<String> all = new ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off"));
        all.addAll(List.of(args));
        SpringApplication application = new SpringApplication(GatewayApplication.class);
        application.addListeners(webServerStarts);
        return catchThrowable(() -> application.run(all.toArray(String[]::new)).close());
    }

    @Test
    void missing_tenants_file(CapturedOutput output) {
        Path missing = dir.resolve("missing.json");

        Throwable failure = startWithTenantsFile(missing);

        assertThat(failure).isNotNull();
        assertThat(webServerStarts.ports).isEmpty();

        FailureAnalysis analysis = new TenantLoadFailureAnalyzer().analyze(failure);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains(missing.toString()).contains("file not found");
        assertThat(analysis.getAction()).contains("TENANTS_FILE");

        assertThat(output.getOut() + output.getErr())
                .contains("APPLICATION FAILED TO START")
                .contains("Tenants file: " + missing);
    }

    @Test
    void unknown_tier(CapturedOutput output) throws IOException {
        Path file = dir.resolve("tenants.json");
        Files.writeString(file, """
                [{ "id": 1, "name": "Acme Corp", "tier": "Platinum" }]
                """, StandardCharsets.UTF_8);

        Throwable failure = startWithTenantsFile(file);

        assertThat(failure).isNotNull();
        assertThat(webServerStarts.ports).isEmpty();
        FailureAnalysis analysis = new TenantLoadFailureAnalyzer().analyze(failure);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains(file.toString())
                .contains("entry 0 (id 1)")
                .contains("Platinum");
        assertThat(output.getOut() + output.getErr()).contains("APPLICATION FAILED TO START");
    }

    @Test
    void zero_period(CapturedOutput output) {
        Throwable failure = start("--QUOTA_PERIOD_SECONDS=0");

        assertThat(failure).isNotNull();
        assertThat(webServerStarts.ports).isEmpty();
        assertThat(output.getOut() + output.getErr())
                .contains("APPLICATION FAILED TO START")
                .contains("quota.periodSeconds")
                .contains("\"0\"");
    }
}
