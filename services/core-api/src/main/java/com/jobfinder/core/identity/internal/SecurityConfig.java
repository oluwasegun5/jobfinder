package com.jobfinder.core.identity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.beans.factory.annotation.Qualifier;

import com.jobfinder.core.shared.SecurityHeaderDefaults;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Stateless JWT resource server. Everything requires a valid access token except the auth
 * entry points and the operational/documentation endpoints listed below.
 *
 * <p>Spring's session-based CSRF token is off: API calls authenticate with a bearer header a foreign site
 * cannot attach. The two cookie-authenticated endpoints (refresh, logout) are covered by the refresh cookie's
 * {@code SameSite=Strict} attribute and, as defence in depth, by {@link CrossSiteRequestFilter}
 * (docs/adr/0037-security-hardening.md). CORS is an explicit origin allow-list ({@link WebSecurityProperties}).
 */
@Configuration
@EnableWebSecurity
@EnableAsync
@EnableConfigurationProperties({ AuthProperties.class, RateLimitProperties.class, WebSecurityProperties.class })
class SecurityConfig {

    private static final String[] PUBLIC_AUTH_POSTS = {
            "/auth/signup", "/auth/verify-email", "/auth/resend-verification", "/auth/login",
            "/auth/google", "/auth/refresh", "/auth/logout", "/auth/forgot-password", "/auth/reset-password" };

    // One-click unsubscribe (docs/adr/0028-notifications.md): the link in an email carries a signed token and must work
    // without signing in. GET only describes what the link does; POST (RFC 8058 one-click) does it.
    private static final String UNSUBSCRIBE = "/notifications/unsubscribe/*";

    // The API docs and health are part of the local/CI contract flow. Gate the docs before production (ADR 0011).
    private static final String[] PUBLIC_OPERATIONAL = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**" };

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ProblemDetailSecurityHandlers handlers,
            WebSecurityProperties web, AuthProperties authProperties,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        SecurityHeaderDefaults.apply(http, web.hsts().maxAge(), web.hsts().includeSubDomains());
        return http
                .cors(cors -> cors.configurationSource(corsSource))
                .addFilterAfter(new CrossSiteRequestFilter(web.effectiveCorsOrigins(authProperties.webBaseUrl()),
                        web.extensionOrigins(), authProperties.refreshCookie().name()), CorsFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_POSTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_OPERATIONAL).permitAll()
                        .requestMatchers(HttpMethod.GET, UNSUBSCRIBE).permitAll()
                        .requestMatchers(HttpMethod.POST, UNSUBSCRIBE).permitAll()
                        // Admin console API (PLAN.md section 9: admin endpoints are role-gated).
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .build();
    }

    /**
     * Exact-match origin allow-list; only the methods and headers the web client uses; no wildcard, and credentials
     * only if switched on for the listed origins. {@code chrome-extension://} origins get no CORS configuration at
     * all: the extension's service worker has host permissions, which exempt it from CORS, so it needs no
     * {@code Access-Control-Allow-Origin} and must not be refused by the CORS filter either (ADR 0035, ADR 0037).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(WebSecurityProperties web, AuthProperties auth) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(web.effectiveCorsOrigins(auth.webBaseUrl()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(List.of("Retry-After"));
        configuration.setAllowCredentials(web.cors().allowCredentials());
        configuration.setMaxAge(web.cors().maxAge());
        return request -> {
            String origin = request.getHeader("Origin");
            if (origin != null && origin.regionMatches(true, 0, "chrome-extension://", 0, 19)) {
                return null;
            }
            return configuration;
        };
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // No clock-skew leeway: access tokens live 15 minutes and that is what we promise.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(properties.jwt().issuer())));
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        return converter;
    }

    private static SecretKey secretKey(AuthProperties properties) {
        return new SecretKeySpec(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
