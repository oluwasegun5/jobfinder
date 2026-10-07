package com.jobfinder.core.billing.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/**
 * Billing's share of the data export: subscriptions, the credit ledger and the AI calls made for the user (feature,
 * model, token counts and cost; never their content). Card details are not held here: they stay with the provider.
 */
@Component
class BillingDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    BillingDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "billing";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("subscriptions", UserDataJson.rows(jdbc, """
                select s.*, p.code as plan_code from subscriptions s join plans p on p.id = s.plan_id
                 where s.user_id = :userId order by s.created_at, s.id
                """, userId, "plan_id"));
        bundle.json("credit-ledger", UserDataJson.rows(jdbc,
                "select t.* from credit_ledger t where t.user_id = :userId order by t.id", userId));
        bundle.json("ai-calls", UserDataJson.rows(jdbc,
                "select t.* from ai_calls t where t.user_id = :userId order by t.created_at, t.id", userId));
    }
}
