package com.jobfinder.core.identity.internal;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AuthDtos {

    private AuthDtos() {
    }

    record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72) String password) {
    }

    record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    record GoogleLoginRequest(@NotBlank @Size(max = 4096) String idToken) {
    }

    record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    record ResetPasswordRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(min = 10, max = 72) String newPassword) {
    }

    /** The refresh token is not in the body: it travels only as an httpOnly cookie. */
    record AuthResponse(String accessToken, String tokenType, long expiresIn) {
    }

    record MeResponse(UUID id, String email, String role, boolean emailVerified) {
    }
}
