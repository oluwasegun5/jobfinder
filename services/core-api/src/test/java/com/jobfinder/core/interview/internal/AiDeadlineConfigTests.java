package com.jobfinder.core.interview.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * ai-service gives up on a request at its own deadline and answers with the usage of the calls it made
 * (docs/adr/0034-mock-interview.md, addendum). That answer is only recorded if core-api is still waiting, so the
 * configured deadline must stay below core-api's read timeout. Checked at startup; these tests pin it, and the defaults.
 */
class AiDeadlineConfigTests {

    private static InterviewProperties prep(Duration readTimeout, Duration aiDeadline) {
        return new InterviewProperties("interview/v1", 12, 20000, Duration.ofSeconds(2), readTimeout,
                Duration.ofMinutes(5), aiDeadline);
    }

    private static MockInterviewProperties mock(Duration readTimeout, Duration aiDeadline) {
        return new MockInterviewProperties("mock_interview/v1", 8, 4000, Duration.ofHours(24), Duration.ofMinutes(4),
                Duration.ofSeconds(2), readTimeout, aiDeadline);
    }

    @Test
    void theDefaultsKeepEveryDeadlineBelowItsReadTimeout() {
        InterviewProperties prep = new Binder(new MapConfigurationPropertySource(java.util.Map.of()))
                .bindOrCreate("app.interview", InterviewProperties.class);
        MockInterviewProperties mock = new Binder(new MapConfigurationPropertySource(java.util.Map.of()))
                .bindOrCreate("app.interview.mock", MockInterviewProperties.class);

        assertThat(prep.readTimeout()).isEqualTo(Duration.ofSeconds(150));
        assertThat(prep.aiDeadline()).isEqualTo(Duration.ofSeconds(120));
        assertThat(prep.aiDeadline()).isLessThan(prep.readTimeout());
        assertThat(mock.readTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(mock.aiDeadline()).isEqualTo(Duration.ofSeconds(75));
        assertThat(mock.aiDeadline()).isLessThan(mock.readTimeout());
    }

    @Test
    void aPrepDeadlineThatIsNotAtLeastFiveSecondsBelowTheReadTimeoutIsRefused() {
        prep(Duration.ofSeconds(150), Duration.ofSeconds(145));
        assertThatThrownBy(() -> prep(Duration.ofSeconds(150), Duration.ofSeconds(146)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app.interview.ai-deadline");
        assertThatThrownBy(() -> prep(Duration.ofSeconds(150), Duration.ofSeconds(150)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prep(Duration.ofSeconds(60), Duration.ofSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMockDeadlineThatIsNotAtLeastFiveSecondsBelowTheReadTimeoutIsRefused() {
        mock(Duration.ofSeconds(90), Duration.ofSeconds(85));
        assertThatThrownBy(() -> mock(Duration.ofSeconds(90), Duration.ofSeconds(86)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app.interview.mock.ai-deadline");
        assertThatThrownBy(() -> mock(Duration.ofSeconds(90), Duration.ofSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
