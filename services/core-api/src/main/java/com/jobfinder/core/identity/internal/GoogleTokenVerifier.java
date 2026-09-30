package com.jobfinder.core.identity.internal;

/** Verifies a Google ID token and returns the identity it asserts. */
interface GoogleTokenVerifier {

    record GoogleIdentity(String subject, String email, boolean emailVerified) {
    }

    /** @throws AuthException if the token is invalid, expired, or not issued for this app */
    GoogleIdentity verify(String idToken);
}
