package com.jobfinder.core.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.MailRecipient;
import com.jobfinder.core.identity.MailRecipients;

/** Reads the address of an active, verified, not deleted user. */
@Component
class MailRecipientReader implements MailRecipients {

    private final JdbcClient jdbc;

    MailRecipientReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MailRecipient> forUser(UUID userId) {
        return jdbc.sql("""
                select id, email from users
                 where id = :id and status = 'ACTIVE' and deleted_at is null and email_verified_at is not null
                """)
                .param("id", userId)
                .query((rs, row) -> new MailRecipient(rs.getObject("id", UUID.class), rs.getString("email")))
                .optional();
    }
}
