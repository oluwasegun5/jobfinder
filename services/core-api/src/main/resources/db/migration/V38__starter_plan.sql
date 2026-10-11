-- G3: the Starter tier between Free (a one-time trial, ADR 0042) and Pro (docs/adr/0036, G3 addendum).
-- monthly_credits is the seeded default; app.billing.plans.starter.monthly-credits overrides it. Prices are configuration
-- only, and a plan without configured prices is not offered, so this row alone changes nothing users can see.
-- V36 and V37 are reserved for the trial work (G1).
INSERT INTO plans (code, name, monthly_credits, active) VALUES ('starter', 'Starter', 2500, TRUE)
ON CONFLICT (code) DO NOTHING;
