package com.jobfinder.core.identity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * The auth flows. Deliberately not {@code @Transactional} as a whole: password hashing is
 * slow and must not hold a database connection, so each flow opens short transactions
 * (via {@link TransactionTemplate} or the token services) around just its writes.
 *
 * <p>Endpoints that take an email never reveal whether an account exists: signup, resend
 * and forgot-password behave identically for known and unknown addresses, and login
 * returns the same error for an unknown email and a wrong password.
 */
@Service
class AuthService {

    /** BCrypt only reads the first 72 bytes and Spring Security rejects longer input. */
    private static final int MAX_PASSWORD_BYTES = 72;

    record Session(JwtService.AccessToken access, RefreshTokenService.Issued refresh) {
    }

    private final UserRepository users;
    private final OAuthAccountRepository oauthAccounts;
    private final GoogleTokenVerifier google;
    private final RefreshTokenService refreshTokens;
    private final EmailTokenService emailTokens;
    private final JwtService jwt;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final AuthProperties properties;
    private final Clock clock;
    /** Compared against when the email is unknown so both paths spend the same time hashing. */
    private final String dummyHash;

    AuthService(UserRepository users, OAuthAccountRepository oauthAccounts, GoogleTokenVerifier google,
            RefreshTokenService refreshTokens, EmailTokenService emailTokens,
            JwtService jwt, RateLimiter rateLimiter, PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events, TransactionTemplate tx, AuthProperties properties, Clock clock) {
        this.users = users;
        this.oauthAccounts = oauthAccounts;
        this.google = google;
        this.refreshTokens = refreshTokens;
        this.emailTokens = emailTokens;
        this.jwt = jwt;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    void signup(String rawEmail, String password, String ip) {
        String email = normalize(rawEmail);
        rateLimiter.check(RateLimitRule.SIGNUP_IP, ip);
        requireAcceptablePassword(password);

        if (users.findByEmail(email).isPresent()) {
            events.publishEvent(new AuthEmailEvent.AccountAlreadyExists(email));
            return;
        }
        String hash = passwordEncoder.encode(password);
        try {
            tx.executeWithoutResult(status -> {
                User user = users.saveAndFlush(new User(email, hash));
                String token = emailTokens.issue(user.getId(), EmailTokenType.VERIFY_EMAIL,
                        properties.verificationTokenTtl());
                events.publishEvent(new AuthEmailEvent.VerifyEmail(email, token));
            });
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent signup for the same address.
            events.publishEvent(new AuthEmailEvent.AccountAlreadyExists(email));
        }
    }

    void verifyEmail(String token, String ip) {
        rateLimiter.check(RateLimitRule.VERIFY_EMAIL_IP, ip);
        tx.executeWithoutResult(status -> {
            UUID userId = emailTokens.consume(token, EmailTokenType.VERIFY_EMAIL)
                    .orElseThrow(AuthException::invalidToken);
            User user = users.findById(userId).orElseThrow(AuthException::invalidToken);
            if (!user.isEmailVerified()) {
                user.markEmailVerified(clock.instant());
                users.save(user);
            }
        });
    }

    void resendVerification(String rawEmail, String ip) {
        String email = normalize(rawEmail);
        rateLimiter.check(RateLimitRule.RESEND_VERIFICATION_IP, ip);
        rateLimiter.check(RateLimitRule.RESEND_VERIFICATION_EMAIL, Tokens.hash(email));

        tx.executeWithoutResult(status -> users.findByEmail(email)
                .filter(user -> user.canAuthenticate() && !user.isEmailVerified())
                .ifPresent(user -> {
                    String token = emailTokens.issue(user.getId(), EmailTokenType.VERIFY_EMAIL,
                            properties.verificationTokenTtl());
                    events.publishEvent(new AuthEmailEvent.VerifyEmail(user.getEmail(), token));
                }));
    }

    Session login(String rawEmail, String password, String ip) {
        String email = normalize(rawEmail);
        rateLimiter.check(RateLimitRule.LOGIN_IP, ip);
        rateLimiter.check(RateLimitRule.LOGIN_EMAIL, Tokens.hash(email));

        User user = users.findByEmail(email).orElse(null);
        String hash = user != null && user.getPasswordHash() != null ? user.getPasswordHash() : dummyHash;
        boolean passwordMatches = fitsBcrypt(password) && passwordEncoder.matches(password, hash);
        if (user == null || !passwordMatches || !user.canAuthenticate()) {
            throw AuthException.invalidCredentials();
        }
        // Only reveal "unverified" to someone who proved they know the password.
        if (!user.isEmailVerified()) {
            throw AuthException.emailNotVerified();
        }
        return new Session(jwt.issue(user), refreshTokens.startFamily(user.getId()));
    }

    /**
     * Signs in with a Google ID token. The Google account is matched by its stable {@code sub}; failing
     * that, by verified email to an existing account, which is then linked. A new verified user is created
     * if neither exists. An email Google has not verified is never trusted.
     */
    Session googleLogin(String idToken, String ip) {
        rateLimiter.check(RateLimitRule.GOOGLE_IP, ip);
        GoogleTokenVerifier.GoogleIdentity identity = google.verify(idToken);
        if (!identity.emailVerified()) {
            throw AuthException.invalidGoogleToken();
        }
        String email = normalize(identity.email());

        User user;
        try {
            user = tx.execute(status -> findOrLinkGoogleUser(identity.subject(), email));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent first sign-in for the same Google account or email.
            user = tx.execute(status -> findOrLinkGoogleUser(identity.subject(), email));
        }
        if (user == null || !user.canAuthenticate()) {
            throw AuthException.invalidCredentials();
        }
        return new Session(jwt.issue(user), refreshTokens.startFamily(user.getId()));
    }

    private User findOrLinkGoogleUser(String subject, String email) {
        var linked = oauthAccounts.findByProviderAndProviderUserId(OAuthAccount.GOOGLE, subject);
        if (linked.isPresent()) {
            return users.findById(linked.get().getUserId()).orElse(null);
        }
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            user = new User(email, null);
            user.markEmailVerified(clock.instant());
            users.saveAndFlush(user);
        } else if (!user.isEmailVerified()) {
            // Someone may have pre-registered this address with a password they chose. Google has proven
            // the real owner, so verify the account and drop that password and any sessions it created.
            user.markEmailVerified(clock.instant());
            user.setPasswordHash(null);
            users.save(user);
            refreshTokens.revokeAllForUser(user.getId());
        }
        oauthAccounts.saveAndFlush(new OAuthAccount(user.getId(), OAuthAccount.GOOGLE, subject));
        return user;
    }

    /** Erases the account: listeners purge their data in this transaction, then the user row goes. */
    void deleteAccount(UUID userId) {
        tx.executeWithoutResult(status -> {
            events.publishEvent(new UserDeletionRequested(userId, clock.instant()));
            users.deleteById(userId);
        });
    }

    Session refresh(String refreshToken, String ip) {
        rateLimiter.check(RateLimitRule.REFRESH_IP, ip);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw AuthException.invalidRefreshToken();
        }
        RefreshTokenService.Rotated rotated = refreshTokens.rotate(refreshToken);
        return new Session(jwt.issue(rotated.user()), rotated.token());
    }

    void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revokeFamilyOf(refreshToken);
        }
    }

    void forgotPassword(String rawEmail, String ip) {
        String email = normalize(rawEmail);
        rateLimiter.check(RateLimitRule.FORGOT_PASSWORD_IP, ip);
        rateLimiter.check(RateLimitRule.FORGOT_PASSWORD_EMAIL, Tokens.hash(email));

        tx.executeWithoutResult(status -> users.findByEmail(email)
                .filter(User::canAuthenticate)
                .ifPresent(user -> {
                    String token = emailTokens.issue(user.getId(), EmailTokenType.RESET_PASSWORD,
                            properties.resetTokenTtl());
                    events.publishEvent(new AuthEmailEvent.ResetPassword(user.getEmail(), token));
                }));
    }

    void resetPassword(String token, String newPassword, String ip) {
        rateLimiter.check(RateLimitRule.RESET_PASSWORD_IP, ip);
        requireAcceptablePassword(newPassword);
        String hash = passwordEncoder.encode(newPassword);

        tx.executeWithoutResult(status -> {
            UUID userId = emailTokens.consume(token, EmailTokenType.RESET_PASSWORD)
                    .orElseThrow(AuthException::invalidToken);
            User user = users.findById(userId).filter(User::canAuthenticate)
                    .orElseThrow(AuthException::invalidToken);
            user.setPasswordHash(hash);
            // Following the emailed link proves control of the mailbox.
            if (!user.isEmailVerified()) {
                user.markEmailVerified(clock.instant());
            }
            users.save(user);
            // A reset must lock out whoever held the old password, including live sessions.
            refreshTokens.revokeAllForUser(userId);
        });
    }

    User currentUser(UUID id) {
        return users.findById(id).filter(User::canAuthenticate).orElseThrow(AuthException::invalidCredentials);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    private static void requireAcceptablePassword(String password) {
        if (!fitsBcrypt(password)) {
            throw AuthException.weakPassword("Password must be at most " + MAX_PASSWORD_BYTES + " bytes.");
        }
    }
}
