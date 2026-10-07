package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.billing.internal.BillingProperties.Pack;
import com.jobfinder.core.billing.internal.BillingProperties.PlanConfig;
import com.jobfinder.core.billing.internal.BillingProperties.Price;

/**
 * The plans (the {@code plans} table, seeded by Flyway) with their configured credits and prices. What a plan grants
 * is {@code app.billing.plans.<code>.monthly-credits} when set, else the seeded column; prices exist only in
 * configuration, and a price is offered only when its provider has keys (an unconfigured provider is never shown).
 */
@Component
class PlanCatalog {

    static final String FREE = "free";
    static final String PRO = "pro";

    record Plan(UUID id, String code, String name, BigDecimal monthlyCredits, boolean active) {
    }

    private final JdbcClient jdbc;
    private final BillingProperties properties;

    PlanCatalog(JdbcClient jdbc, BillingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    List<Plan> all() {
        return jdbc.sql("select id, code, name, monthly_credits, active from plans order by monthly_credits, code")
                .query((rs, row) -> plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("monthly_credits"), rs.getBoolean("active")))
                .list();
    }

    Plan free() {
        return byCode(FREE).orElseThrow(() -> new IllegalStateException("The free plan is missing"));
    }

    Optional<Plan> byCode(String code) {
        return jdbc.sql("select id, code, name, monthly_credits, active from plans where code = :code")
                .param("code", code)
                .query((rs, row) -> plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("monthly_credits"), rs.getBoolean("active")))
                .optional();
    }

    Optional<Plan> byId(UUID id) {
        return jdbc.sql("select id, code, name, monthly_credits, active from plans where id = :id")
                .param("id", id)
                .query((rs, row) -> plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("monthly_credits"), rs.getBoolean("active")))
                .optional();
    }

    private Plan plan(UUID id, String code, String name, BigDecimal seeded, boolean active) {
        BigDecimal credits = properties.plan(code).map(PlanConfig::monthlyCredits).orElse(seeded);
        return new Plan(id, code, name, credits == null ? seeded : credits, active);
    }

    /** The configured prices of a plan by currency, including providers that have no keys (callers filter). */
    Map<String, Price> prices(Plan plan) {
        return properties.plan(plan.code()).map(PlanConfig::prices).orElse(Map.of());
    }

    Map<String, Price> prices(Pack pack) {
        return pack.prices();
    }

    boolean providerConfigured(Provider provider) {
        return switch (provider) {
            case STRIPE -> properties.stripe().configured();
            case PAYSTACK -> properties.paystack().configured();
        };
    }

    /** The price of a plan charged by {@code provider}, if the provider is configured and has one. */
    Optional<Map.Entry<String, Price>> offered(Plan plan, Provider provider) {
        return offered(prices(plan), provider);
    }

    Optional<Map.Entry<String, Price>> offered(Pack pack, Provider provider) {
        return offered(prices(pack), provider);
    }

    private Optional<Map.Entry<String, Price>> offered(Map<String, Price> prices, Provider provider) {
        if (!providerConfigured(provider)) {
            return Optional.empty();
        }
        return prices.entrySet().stream().filter(e -> e.getValue().provider() == provider).findFirst();
    }
}
