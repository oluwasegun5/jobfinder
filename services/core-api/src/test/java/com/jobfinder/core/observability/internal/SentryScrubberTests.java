package com.jobfinder.core.observability.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;

class SentryScrubberTests {

    @Test
    void leavesNothingPersonalInTheEvent() {
        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setUrl("https://api.example.test/auth/login?email=ada@example.test");
        request.setCookies("refresh_token=abc");
        request.setData("{\"cv\":\"my whole CV\"}");
        event.setRequest(request);
        User user = new User();
        user.setEmail("ada@example.test");
        user.setIpAddress("203.0.113.9");
        event.setUser(user);
        event.setServerName("laptop-of-ada");
        event.addBreadcrumb(new Breadcrumb("clicked for ada@example.test"));
        event.setExtra("cv", "my whole CV");
        Message message = new Message();
        message.setFormatted("failed for ada@example.test with Bearer abc.def.ghi");
        message.setMessage("failed for %s");
        message.setParams(List.of("ada@example.test"));
        event.setMessage(message);
        SentryException exception = new SentryException();
        exception.setType("IllegalStateException");
        exception.setValue("duplicate key (email)=(ada@example.test)");
        event.setExceptions(List.of(exception));

        SentryEvent out = new SentryScrubber().execute(event, new Hint());

        assertThat(out.getRequest()).isNull();
        assertThat(out.getUser()).isNull();
        assertThat(out.getServerName()).isNull();
        assertThat(out.getBreadcrumbs()).isNull();
        assertThat(out.getExtras()).isNull();
        assertThat(out.getMessage().getFormatted()).doesNotContain("ada@example.test").doesNotContain("abc.def");
        assertThat(out.getMessage().getParams()).isNull();
        assertThat(out.getExceptions().get(0).getValue()).doesNotContain("ada@example.test");
        assertThat(out.getExceptions().get(0).getType()).isEqualTo("IllegalStateException");
    }
}
