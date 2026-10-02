package com.jobfinder.core.notifications.internal;

import java.util.UUID;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.MailRecipient;
import com.jobfinder.core.identity.MailRecipients;
import com.jobfinder.core.notifications.MailDeliveryException;
import com.jobfinder.core.notifications.RequestedMail;
import com.jobfinder.core.notifications.UserMail;

import jakarta.mail.internet.MimeMessage;

/**
 * Implements {@link UserMail}: a multipart (plain text and HTML) message from the platform's sender address, with a link
 * to the notification settings in the footer. It carries no list-unsubscribe header: this mail is something the user
 * set up for themselves one item at a time, and "unsubscribe" from digests is not what its recipient would mean by it;
 * the master switch (email notifications off) is honoured instead. Everything in {@link RequestedMail} is escaped here.
 */
@Component
class RequestedMailSender implements UserMail {

    private final JavaMailSender sender;
    private final NotificationProperties properties;
    private final MailRecipients recipients;
    private final NotificationPreferencesService preferences;

    RequestedMailSender(JavaMailSender sender, NotificationProperties properties, MailRecipients recipients,
            NotificationPreferencesService preferences) {
        this.sender = sender;
        this.properties = properties;
        this.recipients = recipients;
        this.preferences = preferences;
    }

    @Override
    public Result send(UUID userId, RequestedMail mail) {
        if (!preferences.get(userId).emailEnabled()) {
            return Result.EMAIL_DISABLED;
        }
        MailRecipient to = recipients.forUser(userId).orElse(null);
        if (to == null) {
            return Result.NO_RECIPIENT;
        }
        String settings = properties.webBase() + "/settings/notifications";
        String action = mail.actionPath() == null ? null : properties.webBase() + mail.actionPath();
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.mailFrom());
            helper.setTo(to.email());
            helper.setSubject(MailComposer.oneLine(mail.subject()));
            helper.setText(text(mail, action, settings), html(mail, action, settings));
            message.addHeader("Auto-Submitted", "auto-generated");
            sender.send(message);
            return Result.SENT;
        } catch (jakarta.mail.MessagingException | org.springframework.mail.MailException e) {
            throw new MailDeliveryException(e);
        }
    }

    private static String text(RequestedMail mail, String action, String settings) {
        StringBuilder out = new StringBuilder();
        out.append(MailComposer.oneLine(mail.heading())).append("\n\n");
        for (String line : mail.lines()) {
            out.append(MailComposer.oneLine(line)).append("\n\n");
        }
        if (action != null) {
            out.append(MailComposer.oneLine(mail.actionLabel())).append(": ").append(action).append("\n\n");
        }
        return out.append("--\nNotification settings: ").append(settings).append('\n').toString();
    }

    private static String html(RequestedMail mail, String action, String settings) {
        StringBuilder out = new StringBuilder(1024);
        out.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(MailComposer.esc(MailComposer.oneLine(mail.subject()))).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#f4f4f5;\">")
                .append("<div style=\"max-width:600px;margin:0 auto;padding:24px 16px;font-family:-apple-system,Segoe UI,")
                .append("Helvetica,Arial,sans-serif;color:#18181b;line-height:1.45;\">")
                .append("<h1 style=\"font-size:20px;margin:0 0 12px;\">")
                .append(MailComposer.esc(MailComposer.oneLine(mail.heading()))).append("</h1>");
        for (String line : mail.lines()) {
            out.append("<p style=\"margin:0 0 12px;\">").append(MailComposer.esc(MailComposer.oneLine(line)))
                    .append("</p>");
        }
        if (action != null) {
            out.append("<p style=\"margin:16px 0;\"><a href=\"").append(MailComposer.esc(action))
                    .append("\" style=\"color:#1d4ed8;font-weight:600;\">")
                    .append(MailComposer.esc(MailComposer.oneLine(mail.actionLabel()))).append("</a></p>");
        }
        return out.append("<hr style=\"border:0;border-top:1px solid #e4e4e7;margin:16px 0;\">")
                .append("<p style=\"font-size:12px;color:#71717a;margin:0;\"><a href=\"").append(MailComposer.esc(settings))
                .append("\" style=\"color:#71717a;\">Notification settings</a></p></div></body></html>").toString();
    }
}
