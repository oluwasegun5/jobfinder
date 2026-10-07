package com.jobfinder.core.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.TestcontainersConfiguration;
import com.jobfinder.core.TestcontainersConfiguration.Mailpit;

import jakarta.servlet.http.Cookie;

/**
 * Shared plumbing for the identity integration tests: real Postgres, Redis and Mailpit
 * (Testcontainers), the full Spring Security filter chain via MockMvc, and email assertions
 * against Mailpit's HTTP API. Every test uses its own client IP and email so tests share
 * Redis rate-limit state without interfering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AuthTestSupport {

    protected static final String PASSWORD = "correct-horse-battery";
    protected static final String COOKIE_NAME = "refresh_token";

    private static final AtomicInteger IP_COUNTER = new AtomicInteger();
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private Mailpit mailpit;

    protected static String newIp() {
        int n = IP_COUNTER.incrementAndGet();
        return "10.%d.%d.%d".formatted(n / 65536 % 256, n / 256 % 256, n % 256);
    }

    protected static String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }

    protected ResultActions postJson(String path, String json, String ip) throws Exception {
        return mvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    protected ResultActions postWithCookie(String path, String refreshToken, String ip) throws Exception {
        return mvc.perform(post(path)
                .cookie(new Cookie(COOKIE_NAME, refreshToken))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    protected ResultActions getMe(String accessToken) throws Exception {
        return mvc.perform(get("/auth/me").header("Authorization", "Bearer " + accessToken));
    }

    protected static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    /** Signs up and verifies through the real email link; returns the email address. */
    protected String registerVerifiedUser() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        String token = extractToken(awaitMails(email, 1).get(0));
        postJson("/auth/verify-email", "{\"token\":\"%s\"}".formatted(token), newIp()).andReturn();
        return email;
    }

    /** A logged-in session: the access token from the body and the refresh token from the cookie. */
    protected record Session(String accessToken, String refreshToken) {
    }

    protected Session login(String email, String password, String ip) throws Exception {
        MockHttpServletResponse response = postJson("/auth/login", credentials(email, password), ip)
                .andReturn().getResponse();
        if (response.getStatus() != 200) {
            throw new AssertionError("login failed: " + response.getStatus() + " " + response.getContentAsString());
        }
        return sessionFrom(response);
    }

    protected static Session sessionFrom(MockHttpServletResponse response) throws Exception {
        String access = JsonPath.read(response.getContentAsString(), "$.accessToken");
        return new Session(access, refreshCookieValue(response));
    }

    protected static String refreshCookieValue(MockHttpServletResponse response) {
        String header = setCookieHeader(response);
        Matcher matcher = Pattern.compile(COOKIE_NAME + "=([^;]*)").matcher(header);
        if (!matcher.find()) {
            throw new AssertionError("no refresh cookie in: " + header);
        }
        return matcher.group(1);
    }

    protected static String setCookieHeader(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        if (header == null) {
            throw new AssertionError("no Set-Cookie header");
        }
        return header;
    }

    protected static String extractToken(String emailBody) {
        Matcher matcher = TOKEN.matcher(emailBody);
        if (!matcher.find()) {
            throw new AssertionError("no token link in email: " + emailBody);
        }
        return matcher.group(1);
    }

    /** Waits until Mailpit holds at least {@code count} messages for the recipient; newest first. */
    protected List<String> awaitMails(String to, int count) {
        return Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> mailsTo(to), mails -> mails.size() >= count);
    }

    protected List<String> mailsTo(String to) throws Exception {
        String query = java.net.URLEncoder.encode("to:" + to, java.nio.charset.StandardCharsets.UTF_8);
        String search = fetch("/api/v1/search?query=" + query);
        List<String> ids = JsonPath.read(search, "$.messages[*].ID");
        List<String> bodies = new ArrayList<>();
        for (String id : ids) {
            bodies.add(JsonPath.<String>read(fetch("/api/v1/message/" + id), "$.Text"));
        }
        return bodies;
    }

    private String fetch(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(mailpit.apiBaseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    protected Integer count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
