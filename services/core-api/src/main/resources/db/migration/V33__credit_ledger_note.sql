-- A manual REFUND_ADJUSTMENT (docs/adr/0036-plans-credits-and-payments.md, refund addendum) records why it was made.
-- Nullable and not read by anything that existed before, so older application versions keep working against it; the
-- append-only trigger of V22 is untouched (lines are still never updated).
ALTER TABLE credit_ledger ADD COLUMN note VARCHAR(500);
