package com.jobfinder.core.notifications.internal;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.jobfinder.core.notifications.internal.MailComposer.Mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends a composed digest or alert as a multipart (plain text and HTML) message with the RFC 2369 / RFC 8058
 * list-unsubscribe headers: {@code List-Unsubscribe: <https-url>} and {@code List-Unsubscribe-Post:
 * List-Unsubscribe=One-Click}, so mail clients can offer a one-click unsubscribe that posts to our endpoint. (Receivers
 * such as Gmail also want the message DKIM-signed; that is a property of the production mail provider, see ADR 0028.)
 * Synchronous: the caller records the outcome. Locally the messages land in Mailpit.
 */
@Component
class NotificationMailer {

    private final JavaMailSender sender;
    private final NotificationProperties properties;

    NotificationMailer(JavaMailSender sender, NotificationProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    /** @throws MailException when the message cannot be built or the server refuses it */
    void send(String to, Mail mail) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.mailFrom());
            helper.setTo(to);
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), mail.html());
            message.addHeader("List-Unsubscribe", "<" + mail.oneClickUrl() + ">");
            message.addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            message.addHeader("Precedence", "bulk");
            message.addHeader("Auto-Submitted", "auto-generated");
            sender.send(message);
        } catch (MessagingException e) {
            throw new org.springframework.mail.MailPreparationException(e);
        }
    }
}
