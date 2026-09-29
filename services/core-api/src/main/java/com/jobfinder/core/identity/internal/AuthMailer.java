package com.jobfinder.core.identity.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends the plain-text auth emails. Locally these land in Mailpit (http://localhost:8025). */
@Component
class AuthMailer {

    private static final Logger log = LoggerFactory.getLogger(AuthMailer.class);

    private final JavaMailSender mailSender;
    private final AuthProperties properties;

    AuthMailer(JavaMailSender mailSender, AuthProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(AuthEmailEvent event) {
        SimpleMailMessage message = switch (event) {
            case AuthEmailEvent.VerifyEmail e -> compose(e.to(), "Verify your JobFinder email",
                    "Welcome to JobFinder! Confirm your email address to activate your account:\n\n"
                            + link("/verify-email", e.token())
                            + "\n\nThe link expires in " + properties.verificationTokenTtl().toHours() + " hours."
                            + " If you did not sign up, you can ignore this email.");
            case AuthEmailEvent.ResetPassword e -> compose(e.to(), "Reset your JobFinder password",
                    "We received a request to reset your password. Choose a new one here:\n\n"
                            + link("/reset-password", e.token())
                            + "\n\nThe link expires in " + properties.resetTokenTtl().toMinutes() + " minutes."
                            + " If you did not ask for this, you can ignore this email; your password is unchanged.");
            case AuthEmailEvent.AccountAlreadyExists e -> compose(e.to(), "You already have a JobFinder account",
                    "Someone (hopefully you) tried to sign up with this email address, but it already has an account.\n\n"
                            + "If you forgot your password, reset it here:\n\n"
                            + properties.webBaseUrl() + "/forgot-password"
                            + "\n\nIf this wasn't you, no action is needed.");
        };
        try {
            mailSender.send(message);
        } catch (RuntimeException e) {
            // Never log the address or token: both are personal/sensitive.
            log.error("Failed to send {} email", event.getClass().getSimpleName(), e);
        }
    }

    private SimpleMailMessage compose(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        return message;
    }

    private String link(String path, String token) {
        return properties.webBaseUrl() + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }
}
