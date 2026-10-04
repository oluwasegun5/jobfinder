package com.jobfinder.core.identity;

import com.jobfinder.core.CoversEndpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

class LoginAndAccessTokenTests extends AuthTestSupport {

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    @Test
    void loginReturnsA15MinuteAccessTokenForTheUser() throws Exception {
        String email = registerVerifiedUser();

        MockHttpServletResponse response = postJson("/auth/login", credentials(email, PASSWORD), newIp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();

        Jwt jwt = jwtDecoder.decode(sessionFrom(response).accessToken());
        String userId = jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
        assertThat(jwt.getSubject()).isEqualTo(userId);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void refreshTokenIsAnHttpOnlyCookieAndNeverInTheBody() throws Exception {
        String email = registerVerifiedUser();

        MockHttpServletResponse response = postJson("/auth/login", credentials(email, PASSWORD), newIp())
                .andReturn().getResponse();

        String cookie = setCookieHeader(response);
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/core/auth").contains("Max-Age=2592000");
        String refreshToken = refreshCookieValue(response);
        assertThat(refreshToken).hasSizeGreaterThan(30);
        assertThat(response.getContentAsString()).doesNotContain(refreshToken);
    }

    @Test
    void refreshTokenIsStoredOnlyAsAHash() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());

        assertThat(count("select count(*) from refresh_tokens where token_hash = ?", session.refreshToken()))
                .isZero();
        assertThat(count("select count(*) from refresh_tokens r join users u on u.id = r.user_id "
                + "where u.email = ? and length(r.token_hash) = 64", email)).isEqualTo(1);
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String email = registerVerifiedUser();

        String wrongPassword = postJson("/auth/login", credentials(email, "not-the-password-1"), newIp())
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = postJson("/auth/login", credentials(newEmail(), PASSWORD), newIp())
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    @Test
    void emailMatchingIsCaseInsensitive() throws Exception {
        String email = registerVerifiedUser();

        postJson("/auth/login", credentials(email.toUpperCase(), PASSWORD), newIp()).andExpect(status().isOk());
    }

    @Test
    void disabledAccountsCannotLogIn() throws Exception {
        String email = registerVerifiedUser();
        jdbc.update("update users set status = 'DISABLED' where email = ?", email);

        postJson("/auth/login", credentials(email, PASSWORD), newIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @CoversEndpoints({"GET /auth/me"})
    @Test
    void meReturnsTheAuthenticatedUsersOwnRecordAndNooneElses() throws Exception {
        String emailA = registerVerifiedUser();
        String emailB = registerVerifiedUser();
        Session a = login(emailA, PASSWORD, newIp());
        Session b = login(emailB, PASSWORD, newIp());

        getMe(a.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(emailA))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.emailVerified").value(true));
        getMe(b.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(emailB));
    }

    @Test
    void protectedEndpointsRejectMissingOrMalformedTokensWithAProblemDetail() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));

        getMe("not.a.jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokensAreRejected() throws Exception {
        String email = registerVerifiedUser();
        String userId = jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
        Instant past = Instant.now().minus(Duration.ofHours(1));

        String expired = encode(jwtEncoder, userId, "jobfinder-core-api", past, past.plusSeconds(60));

        getMe(expired).andExpect(status().isUnauthorized());
    }

    @Test
    void tokensSignedWithAnotherKeyOrIssuedByAnotherIssuerAreRejected() throws Exception {
        String email = registerVerifiedUser();
        String userId = jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
        Instant now = Instant.now();

        JwtEncoder otherKey = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec("a-completely-different-secret-key-0123456789".getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256")));
        String forged = encode(otherKey, userId, "jobfinder-core-api", now, now.plusSeconds(600));
        String wrongIssuer = encode(jwtEncoder, userId, "someone-else", now, now.plusSeconds(600));
        String valid = encode(jwtEncoder, userId, "jobfinder-core-api", now, now.plusSeconds(600));

        getMe(forged).andExpect(status().isUnauthorized());
        getMe(wrongIssuer).andExpect(status().isUnauthorized());
        getMe(valid).andExpect(status().isOk()); // control: the helper itself produces acceptable tokens
    }

    @Test
    void unsignedTokensAreRejected() throws Exception {
        String email = registerVerifiedUser();
        String userId = jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
        var base64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64.encodeToString(("{\"sub\":\"%s\",\"role\":\"ADMIN\",\"iss\":\"jobfinder-core-api\","
                + "\"exp\":%d}").formatted(userId, Instant.now().plusSeconds(600).getEpochSecond())
                .getBytes(StandardCharsets.UTF_8));

        getMe(header + "." + payload + ".").andExpect(status().isUnauthorized());
    }

    private static String encode(JwtEncoder encoder, String subject, String issuer, Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(subject)
                .issuedAt(issuedAt).expiresAt(expiresAt).claim("role", "USER").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
