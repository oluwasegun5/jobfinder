package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** The sender and the links of the auth emails come from configuration; no mail server is involved. */
class AuthMailerTests {

    @Configuration
    @EnableConfigurationProperties(AuthProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Props.class)
            .withPropertyValues("JWT_SECRET=" + "a1b2c3d4".repeat(5));

    private SimpleMailMessage send(AuthProperties properties, AuthEmailEvent event) {
        JavaMailSender mail = mock(JavaMailSender.class);
        new AuthMailer(mail, properties).on(event);
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        return sent.getValue();
    }

    @Test
    void theSenderComesFromNotificationsMailFrom() {
        runner.withPropertyValues("NOTIFICATIONS_MAIL_FROM=no-reply@example.test").run(context -> {
            AuthProperties properties = context.getBean(AuthProperties.class);

            assertThat(properties.mailFrom()).isEqualTo("no-reply@example.test");
            assertThat(send(properties, new AuthEmailEvent.VerifyEmail("a@example.test", "tok")).getFrom())
                    .isEqualTo("no-reply@example.test");
            assertThat(send(properties, new AuthEmailEvent.ResetPassword("a@example.test", "tok")).getFrom())
                    .isEqualTo("no-reply@example.test");
            assertThat(send(properties, new AuthEmailEvent.AccountAlreadyExists("a@example.test")).getFrom())
                    .isEqualTo("no-reply@example.test");
        });
    }

    @Test
    void theLocalDefaultSenderIsUnchanged() {
        runner.run(context -> assertThat(context.getBean(AuthProperties.class).mailFrom())
                .isEqualTo("no-reply@jobfinder.local"));
    }

    @Test
    void theVerificationLinkPointsAtWebBaseUrl() {
        runner.withPropertyValues("WEB_BASE_URL=https://app.example.test").run(context -> {
            SimpleMailMessage message = send(context.getBean(AuthProperties.class),
                    new AuthEmailEvent.VerifyEmail("a@example.test", "tok"));

            assertThat(message.getText()).contains("https://app.example.test/verify-email?token=tok")
                    .doesNotContain("localhost");
        });
    }
}
