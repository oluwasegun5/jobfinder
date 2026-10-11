/**
 * Activation milestones and the identity digests the trial gate keys on (docs/adr/0042-trial-credit-abuse-gate.md).
 *
 * <p>The milestone events ({@link com.jobfinder.core.activation.EmailVerified},
 * {@link com.jobfinder.core.activation.ResumeParsed}, {@link com.jobfinder.core.activation.OnboardingCompleted},
 * {@link com.jobfinder.core.activation.FirstMatchesViewed}) are published by the modules that own the facts (identity,
 * profile, jobs) and consumed by billing's trial gate (and the funnel later). They carry ids and keyed digests only,
 * never a raw email, phone number or file, so raw values never cross modules.
 *
 * <p>{@link com.jobfinder.core.activation.IdentityNormaliser} turns an email, phone, Google account or file into its
 * canonical claim key; {@link com.jobfinder.core.activation.IdentityDigester} turns that into HMAC-SHA-256 digests under
 * every live pepper version. The canonical form is for the claim key only, never for login.
 *
 * <p>Depends on no other module, so identity, profile, jobs and billing can all use it without a cycle.
 */
package com.jobfinder.core.activation;
