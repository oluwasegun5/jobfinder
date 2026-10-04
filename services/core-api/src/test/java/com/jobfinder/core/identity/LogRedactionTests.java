package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * "Never log CV, answer, profile, job text, tokens or secrets" (CLAUDE.md, ASVS 7.1.1): requests that carry a fake
 * password, bearer token, refresh cookie, email address and CV text are sent through the failure paths, and every
 * line the application logs (message, arguments, exception text) is searched for them.
 *
 * <p>There is no redaction layer in core-api: the rule is kept by never passing such values to a logger, and this test
 * is the evidence. The gaps it cannot cover (ai-service logs, request logging by a future proxy) are listed in
 * docs/security-review.md for P6.3.
 */
class LogRedactionTests extends AuthTestSupport {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void capture() {
        captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void release() {
        root.detachAppender(captured);
    }

    private String everythingLogged() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(captured.list)) {
            all.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                all.append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        return all.toString();
    }

    @Test
    void passwordsEmailsAndTokensNeverReachTheLogs() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "leak-" + marker + "@example.test";
        String password = "pw-LEAK-" + marker;
        String bearer = "eyJhbGciOiJIUzI1NiJ9.LEAK" + marker + ".sig" + marker;
        String cookie = "refresh-LEAK-" + marker;
        String cvText = "CV-TEXT-LEAK-" + marker;

        // unknown account, wrong password, existing account signup, malformed JSON carrying CV text,
        // an invalid bearer token, a bogus refresh cookie
        postJson("/auth/login", credentials(email, password), newIp());
        postJson("/auth/signup", credentials(email, password), newIp());
        postJson("/auth/signup", credentials(email, password), newIp());
        postJson("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"cv\":\"" + cvText,
                newIp());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me")
                .header("Authorization", "Bearer " + bearer));
        postWithCookie("/auth/refresh", cookie, newIp());
        postWithCookie("/auth/logout", cookie, newIp());
        String owner = registerVerifiedUser();
        Session session = login(owner, PASSWORD, newIp());
        mvc.perform(put("/profile").header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"headline\":\"" + cvText + "\", broken"));
        mvc.perform(post("/jobs/" + UUID.randomUUID() + "/tailor").header("Authorization",
                "Bearer " + session.accessToken()));

        String logs = everythingLogged();
        assertThat(logs).isNotBlank();
        assertThat(logs).doesNotContain(password).doesNotContain(bearer).doesNotContain(cookie)
                .doesNotContain(cvText).doesNotContain(session.accessToken()).doesNotContain(PASSWORD)
                .doesNotContain(email).doesNotContain(owner);
    }
}
