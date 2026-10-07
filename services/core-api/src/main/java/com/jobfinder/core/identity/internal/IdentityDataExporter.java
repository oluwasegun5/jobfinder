package com.jobfinder.core.identity.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/** Identity's share of the data export: the account record and how the user signs in. No hashes, no tokens. */
@Component
class IdentityDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    IdentityDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "account";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("account", UserDataJson.rows(jdbc, """
                select u.id, u.email, u.role, u.status, u.email_verified_at, u.ai_consent_version, u.ai_consent_at,
                       (u.password_hash is not null) as has_password, u.created_at, u.updated_at
                  from users u where u.id = :userId
                """, userId));
        bundle.json("sign-in-providers", UserDataJson.rows(jdbc, """
                select o.provider, o.provider_user_id, o.created_at from oauth_accounts o
                 where o.user_id = :userId order by o.created_at
                """, userId));
    }
}
