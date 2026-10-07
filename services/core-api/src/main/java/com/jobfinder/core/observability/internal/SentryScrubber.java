package com.jobfinder.core.observability.internal;

import java.util.List;

import com.jobfinder.core.observability.PiiRedactor;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions.BeforeSendCallback;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;

/**
 * What Sentry may receive: the exception type, a redacted message and the stack frames. Everything that could carry
 * personal data is removed before the event leaves the process: the request (URL, headers, cookies, body), the user,
 * the host name, the breadcrumbs and the extra contexts. Messages and exception texts go through {@link PiiRedactor}.
 */
final class SentryScrubber implements BeforeSendCallback {

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        event.setRequest(null);
        event.setUser(null);
        event.setServerName(null);
        event.setBreadcrumbs(null);
        event.setExtras(null);
        event.getContexts().remove("request");
        Message message = event.getMessage();
        if (message != null) {
            message.setFormatted(PiiRedactor.redact(message.getFormatted()));
            message.setMessage(PiiRedactor.redact(message.getMessage()));
            message.setParams(null);
        }
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            exceptions.forEach(e -> e.setValue(PiiRedactor.redact(e.getValue())));
        }
        return event;
    }
}
