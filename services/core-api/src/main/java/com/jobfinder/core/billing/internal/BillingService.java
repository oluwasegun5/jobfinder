package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.billing.Allowance;
import com.jobfinder.core.billing.internal.BillingProperties.Pack;
import com.jobfinder.core.billing.internal.BillingProperties.Price;
import com.jobfinder.core.billing.internal.PaymentProvider.CheckoutContext;
import com.jobfinder.core.billing.internal.PaymentProvider.PaymentProviderException;
import com.jobfinder.core.billing.internal.PlanCatalog.Plan;
import com.jobfinder.core.billing.internal.SubscriptionStore.Status;
import com.jobfinder.core.billing.internal.SubscriptionStore.Subscription;
import com.jobfinder.core.identity.MailRecipients;
import com.jobfinder.core.identity.RateLimits;
import com.jobfinder.core.shared.ApiException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * What the signed-in user sees and does about their plan (docs/adr/0036-plans-credits-and-payments.md): the catalogue,
 * their own plan, balance and ledger, starting a hosted checkout, and cancelling. Every method takes the caller's user
 * id and reads or writes only that user's rows; there is no way to name another user.
 */
@Service
class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 100;

    record PriceView(String provider, String currency, long amountMinor) {
    }

    record PlanView(String code, String name, BigDecimal monthlyCredits, List<PriceView> prices) {
    }

    record PackView(String id, String name, BigDecimal credits, List<PriceView> prices) {
    }

    record Catalogue(List<PlanView> plans, List<PackView> packs, BigDecimal rolloverCapCredits) {
    }

    record SubscriptionView(String provider, String status, Instant currentPeriodEnd, boolean cancelAtPeriodEnd,
            Instant graceEndsAt) {
    }

    record PlanSummary(String code, String name, BigDecimal monthlyCredits) {
    }

    record Me(PlanSummary plan, String status, SubscriptionView subscription, BigDecimal balance,
            BigDecimal grantedThisPeriod, BigDecimal usedThisPeriod, Instant periodStart, Instant periodEnd,
            AllowanceView allowance) {
    }

    record AllowanceView(BigDecimal dailyCap, BigDecimal used, BigDecimal remaining, Instant resetsAt) {
    }

    record LedgerLine(long id, Instant createdAt, BigDecimal delta, String reason, BigDecimal balanceAfter,
            String feature) {
    }

    record LedgerPage(List<LedgerLine> items, String nextCursor) {
    }

    record CheckoutLinkView(String url) {
    }

    private final PlanCatalog plans;
    private final SubscriptionStore subscriptions;
    private final CreditLedgerStore ledger;
    private final CreditGrants grants;
    private final DailyCapService caps;
    private final AiCallStore calls;
    private final BillingProperties properties;
    private final Map<Provider, PaymentProvider> providers = new EnumMap<>(Provider.class);
    private final MailRecipients mail;
    private final RateLimits rateLimits;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    BillingService(PlanCatalog plans, SubscriptionStore subscriptions, CreditLedgerStore ledger, CreditGrants grants,
            DailyCapService caps, AiCallStore calls, BillingProperties properties, List<PaymentProvider> providers,
            MailRecipients mail, RateLimits rateLimits, JdbcClient jdbc, TransactionTemplate tx,
            MeterRegistry meters, Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.ledger = ledger;
        this.grants = grants;
        this.caps = caps;
        this.calls = calls;
        this.properties = properties;
        providers.forEach(p -> this.providers.put(p.provider(), p));
        this.mail = mail;
        this.rateLimits = rateLimits;
        this.jdbc = jdbc;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    // ---- catalogue ------------------------------------------------------------------------------------------

    Catalogue catalogue() {
        List<PlanView> planViews = plans.all().stream().filter(Plan::active)
                .map(p -> new PlanView(p.code(), p.name(), p.monthlyCredits(), offeredPrices(plans.prices(p))))
                .toList();
        List<PackView> packViews = properties.packs().stream()
                .map(p -> new PackView(p.id(), p.name() == null ? p.id() : p.name(), p.credits(),
                        offeredPrices(p.prices())))
                .filter(p -> !p.prices().isEmpty()).toList();
        return new Catalogue(planViews, packViews, properties.rolloverCapCredits());
    }

    private List<PriceView> offeredPrices(Map<String, Price> prices) {
        List<PriceView> out = new ArrayList<>();
        prices.forEach((currency, price) -> {
            if (plans.providerConfigured(price.provider())) {
                out.add(new PriceView(price.provider().name(), currency.toUpperCase(java.util.Locale.ROOT),
                        price.amountMinor()));
            }
        });
        return out;
    }

    // ---- the caller's account -------------------------------------------------------------------------------

    /** The caller's plan and balance. Also gives a Free user this month's credits if they have none yet. */
    Me me(UUID userId) {
        Instant now = Instant.now(clock);
        grants.grantFreeIfDue(userId, now);
        Optional<Subscription> live = subscriptions.live(userId);
        Plan plan = live.flatMap(s -> plans.byId(s.planId())).orElseGet(plans::free);
        Instant periodStart;
        Instant periodEnd;
        if (live.isPresent() && live.get().currentPeriodEnd() != null) {
            periodEnd = live.get().currentPeriodEnd();
            periodStart = periodEnd.atZone(ZoneOffset.UTC).minusMonths(1).toInstant();
        } else {
            ZonedDateTime month = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC);
            periodStart = month.toInstant();
            periodEnd = month.plusMonths(1).toInstant();
        }
        BigDecimal granted = jdbc.sql("select coalesce(sum(delta), 0) from credit_ledger where user_id = :u and "
                + "reason = 'PLAN_GRANT' and created_at >= :from").param("u", userId)
                .param("from", OffsetDateTime.ofInstant(periodStart, ZoneOffset.UTC)).query(BigDecimal.class)
                .single();
        BigDecimal used = calls.creditsUsed(userId, periodStart, now.plusSeconds(1));
        Allowance allowance = caps.allowance(userId, now);
        SubscriptionView view = live.map(s -> view(s)).orElseGet(() -> subscriptions.latest(userId)
                .filter(s -> s.status() == Status.PENDING).map(s -> view(s)).orElse(null));
        String status = live.map(s -> s.status().name()).orElse("FREE");
        return new Me(new PlanSummary(plan.code(), plan.name(), plan.monthlyCredits()), status, view,
                ledger.balance(userId), granted, used, periodStart, periodEnd,
                new AllowanceView(allowance.dailyCap(), allowance.used(), allowance.remaining(),
                        allowance.resetsAt()));
    }

    private SubscriptionView view(Subscription s) {
        Instant graceEnds = s.status() == Status.PAST_DUE && s.pastDueSince() != null
                ? s.pastDueSince().plus(properties.gracePeriod()) : null;
        return new SubscriptionView(s.provider().name(), s.status().name(), s.currentPeriodEnd(),
                s.cancelAtPeriodEnd(), graceEnds);
    }

    /** The caller's ledger, newest first, {@code limit} lines from just after the cursor. */
    LedgerPage ledger(UUID userId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        Long before = decode(cursor);
        var query = jdbc.sql("""
                select l.id, l.created_at, l.delta, l.reason, l.balance_after, c.feature
                  from credit_ledger l left join ai_calls c on c.id = l.ai_call_id
                 where l.user_id = :u and (cast(:before as bigint) is null or l.id < cast(:before as bigint))
                 order by l.id desc limit :n
                """).param("u", userId).param("n", size + 1);
        query = before == null ? query.param("before", null, java.sql.Types.BIGINT) : query.param("before", before);
        List<LedgerLine> lines = query.query((rs, row) -> new LedgerLine(rs.getLong("id"),
                rs.getTimestamp("created_at").toInstant(), rs.getBigDecimal("delta"), rs.getString("reason"),
                rs.getBigDecimal("balance_after"), rs.getString("feature"))).list();
        boolean more = lines.size() > size;
        List<LedgerLine> page = more ? lines.subList(0, size) : lines;
        return new LedgerPage(List.copyOf(page), more ? encode(page.get(page.size() - 1).id()) : null);
    }

    private static String encode(long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(id).getBytes());
    }

    private static Long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor)));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor",
                    "The cursor is not valid for this request; start again from the first page.");
        }
    }

    // ---- checkout and cancel --------------------------------------------------------------------------------

    CheckoutLinkView checkout(UUID userId, String planCode, String packId, String providerName) {
        rateLimits.check("billing-checkout", userId.toString(), properties.limits().checkoutPerHour(),
                Duration.ofHours(1));
        if ((planCode == null) == (packId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_checkout",
                    "Choose either a plan or a credit pack.");
        }
        Provider provider = Provider.parse(providerName).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "provider_not_offered", "That payment provider is not offered."));
        String email = mail.forUser(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "email_not_verified", "Verify your email address before paying.")).email();
        PaymentProvider gateway = providers.get(provider);
        Instant now = Instant.now(clock);
        String reference = "jf_" + UUID.randomUUID().toString().replace("-", "");
        CheckoutContext context = new CheckoutContext(userId, email, reference, properties.checkout().returnUrl(),
                properties.checkout().cancelUrl());
        try {
            if (planCode != null) {
                Plan plan = plans.byCode(planCode).filter(Plan::active).filter(p -> !PlanCatalog.FREE.equals(p.code()))
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "plan_not_found",
                                "That plan does not exist."));
                Map.Entry<String, Price> price = plans.offered(plan, provider).orElseThrow(() -> notOffered());
                if (subscriptions.live(userId).isPresent()) {
                    throw new ApiException(HttpStatus.CONFLICT, "already_subscribed",
                            "You already have a paid plan. Cancel it before choosing another.");
                }
                tx.executeWithoutResult(s -> subscriptions.startPending(userId, plan.id(), provider, now));
                String url = gateway.checkoutPlan(context, plan, price.getKey(), price.getValue()).url();
                count("plan", provider);
                return new CheckoutLinkView(url);
            }
            Pack pack = properties.pack(packId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                    "pack_not_found", "That credit pack does not exist."));
            Map.Entry<String, Price> price = plans.offered(pack, provider).orElseThrow(() -> notOffered());
            String url = gateway.checkoutPack(context, pack, price.getKey(), price.getValue()).url();
            count("pack", provider);
            return new CheckoutLinkView(url);
        } catch (PaymentProviderException e) {
            log.warn("Checkout could not be started: provider={} cause={}", provider.slug(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "payment_provider_unavailable",
                    "The payment provider could not be reached. Try again in a moment.");
        }
    }

    private static ApiException notOffered() {
        return new ApiException(HttpStatus.BAD_REQUEST, "provider_not_offered",
                "That payment provider is not offered for this item.");
    }

    private void count(String kind, Provider provider) {
        meters.counter("billing.checkouts", "kind", kind, "provider", provider.slug()).increment();
    }

    /** Stops the caller's paid plan renewing; they keep it until the period ends. Idempotent (also when there is none). */
    Me cancel(UUID userId) {
        rateLimits.check("billing-cancel", userId.toString(), properties.limits().cancelPerHour(),
                Duration.ofHours(1));
        Optional<Subscription> live = subscriptions.live(userId);
        if (live.isPresent() && !live.get().cancelAtPeriodEnd()) {
            Subscription sub = live.get();
            if (sub.providerRef() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "subscription_not_ready",
                        "Your subscription is still being set up. Try again in a minute.");
            }
            try {
                providers.get(sub.provider()).cancelAtPeriodEnd(sub.providerRef());
            } catch (PaymentProviderException e) {
                log.warn("Cancellation could not be sent: provider={} cause={}", sub.provider().slug(),
                        e.getMessage());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "payment_provider_unavailable",
                        "The payment provider could not be reached. Try again in a moment.");
            }
            tx.executeWithoutResult(s -> {
                ledger.lock(userId);
                subscriptions.live(userId).filter(x -> x.id().equals(sub.id())).ifPresent(current ->
                        subscriptions.save(new Subscription(current.id(), current.userId(), current.planId(),
                                current.provider(), current.providerRef(), current.providerCustomer(),
                                current.status(), current.currentPeriodEnd(), true, current.pastDueSince(),
                                current.lastEventAt(), current.createdAt(), Instant.now(clock)),
                                Instant.now(clock)));
            });
        }
        return me(userId);
    }
}
