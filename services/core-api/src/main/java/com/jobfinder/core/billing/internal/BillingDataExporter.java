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
        // The trial (docs/adr/0042): its state, and the eligibility claims by kind and date. The keyed hashes are left
        // out: they say nothing to the user, and a person can have their rows found by giving the email or phone.
        // Explicit columns: the reasons and the admin review (ADR 0047) stay out, so the export is not a way to learn which
        // signal fired.
        bundle.json("trial", UserDataJson.rows(jdbc, """
                select t.user_id, t.state, t.milestones, t.risk_score, t.started_at, t.expires_at, t.ended_at,
                       t.created_at, t.updated_at
                  from trial_accounts t where t.user_id = :userId
                """, userId));
        bundle.json("trial-claims", UserDataJson.rows(jdbc, """
                select t.kind, t.first_claimed_at, t.last_seen_at, t.expires_at from trial_identity_claims t
                 where t.user_id = :userId order by t.first_claimed_at, t.kind
                """, userId));
    }
}
