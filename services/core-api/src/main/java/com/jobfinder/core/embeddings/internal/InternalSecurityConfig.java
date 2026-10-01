package com.jobfinder.core.embeddings.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentication for {@code /internal/**}: one filter chain of its own, matched before the user-facing one, that
 * accepts only the shared service token ({@code X-Service-Token}, the same secret core-api sends to ai-service).
 * A user's JWT, even an admin's, does not open these endpoints.
 */
@Configuration
class InternalSecurityConfig {

    static final String HEADER = "X-Service-Token";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain internalFilterChain(HttpSecurity http, @Value("${app.ai-service.token}") String token)
            throws Exception {
        // Built here, not as a bean: a Filter bean would also be registered on every servlet path.
        ServiceTokenFilter filter = new ServiceTokenFilter(token);
        return http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, e) -> reject(response)))
                .build();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"Missing or invalid service token\",\"code\":\"invalid_service_token\"}");
    }

    private static final class ServiceTokenFilter extends OncePerRequestFilter {

        private final byte[] expected;

        ServiceTokenFilter(String token) {
            this.expected = token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String presented = request.getHeader(HEADER);
            if (presented != null
                    && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "ai-service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
            }
            chain.doFilter(request, response);
        }
    }
}
