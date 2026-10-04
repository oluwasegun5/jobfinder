package com.jobfinder.core.billing.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code POST /webhooks/**} has a filter chain of its own, matched before the user-facing one: no JWT (a provider has
 * none), no session, CSRF off (there is no browser session to forge a request against). What authenticates a delivery
 * is its signature over the raw body, checked in {@link WebhookService}; nothing else is reachable through this chain.
 */
@Configuration
class WebhookSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/webhooks/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/webhooks/stripe", "/webhooks/paystack").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
