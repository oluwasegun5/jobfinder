package com.jobfinder.core.identity.internal;

import java.time.Clock;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.identity.Role;

/**
 * Bootstraps the first admin from {@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD}. Idempotent: an existing
 * account with that email is promoted (its password is left alone); otherwise, if a password is given,
 * a verified ADMIN account is created. Nothing happens when {@code ADMIN_EMAIL} is unset.
 */
@Component
class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final AuthProperties properties;
    private final Clock clock;

    AdminSeeder(UserRepository users, PasswordEncoder passwordEncoder, TransactionTemplate tx,
            AuthProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(properties.admin().email(), properties.admin().password());
    }

    void seed(String rawEmail, String password) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        boolean usablePassword = password != null && password.length() >= 10
                && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
        if (password != null && !password.isBlank() && !usablePassword) {
            log.warn("ADMIN_PASSWORD must be 10-72 bytes; ignoring it");
        }
        String hash = usablePassword ? passwordEncoder.encode(password) : null;
        try {
            tx.executeWithoutResult(status -> {
                User existing = users.findByEmail(email).orElse(null);
                if (existing != null) {
                    if (existing.getRole() != Role.ADMIN) {
                        existing.promoteToAdmin();
                        users.save(existing);
                        log.info("Promoted existing account to ADMIN");
                    }
                } else if (hash != null) {
                    User admin = new User(email, hash);
                    admin.markEmailVerified(clock.instant());
                    admin.promoteToAdmin();
                    users.saveAndFlush(admin);
                    log.info("Created ADMIN account");
                } else {
                    log.warn("ADMIN_EMAIL is set but no such account exists and ADMIN_PASSWORD is empty; nothing seeded");
                }
            });
        } catch (DataIntegrityViolationException e) {
            // Another instance seeded the same admin at the same moment.
            log.debug("Admin already seeded concurrently");
        }
    }
}
