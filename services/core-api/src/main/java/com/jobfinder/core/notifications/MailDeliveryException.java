package com.jobfinder.core.notifications;

/** A {@link UserMail} that could not be sent. The cause is kept out of the message: a mail server's text can hold the address. */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(Throwable cause) {
        super("The mail could not be sent: " + cause.getClass().getSimpleName(), cause);
    }
}
