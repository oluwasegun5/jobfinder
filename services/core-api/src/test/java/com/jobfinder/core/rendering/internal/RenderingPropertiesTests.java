package com.jobfinder.core.rendering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RenderingPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RenderingConfig.class);

    @Test
    void theDownloadLinkLivesFiveMinutesByDefault() {
        runner.run(context -> assertThat(context.getBean(RenderingProperties.class).downloadUrlTtl())
                .isEqualTo(Duration.ofMinutes(5)));
    }

    @Test
    void theLifetimeComesFromConfiguration() {
        runner.withPropertyValues("app.rendering.download-url-ttl=45s")
                .run(context -> assertThat(context.getBean(RenderingProperties.class).downloadUrlTtl())
                        .isEqualTo(Duration.ofSeconds(45)));
    }
}
