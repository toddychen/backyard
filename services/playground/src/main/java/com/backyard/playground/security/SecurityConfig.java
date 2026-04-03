package com.backyard.playground.security;

import com.backyard.playground.controller.GlobalExceptionHandler.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configures the Spring Security filter chain.
 *
 * <h3>How the security pipeline works</h3>
 *
 * <p>
 * Every HTTP request passes through Spring Security's {@code FilterChainProxy}
 * before reaching a controller. The key filters in order:
 *
 * <ol>
 * <li>{@link JwtAuthenticationFilter} (registered here via
 * {@code addFilterBefore}) — extracts the Bearer token, validates it, and sets
 * the authenticated principal in {@code
 *       SecurityContextHolder}. Does NOT reject requests — it only identifies
 * the caller.
 * <li>{@code AuthorizationFilter} (built-in, configured by
 * {@code authorizeHttpRequests}) — reads the {@code SecurityContextHolder} and
 * enforces the access rules defined below. Rejects with 401/403 if the rules
 * are not satisfied.
 * </ol>
 *
 * <h3>Error responses</h3>
 *
 * <p>
 * {@code exceptionHandling} overrides Spring Security's default HTML error
 * pages with JSON bodies that match the {@code ErrorResponse} format used by
 * {@code GlobalExceptionHandler}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless API — no CSRF needed (no cookies/sessions)
                .csrf(csrf -> csrf.disable())
                // No server-side sessions — auth state lives in the JWT
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Access rules — only explicitly protected paths
                // require auth; everything else is public by default.
                // New endpoints are safe to add without touching this
                // config unless they need protection.
                .authorizeHttpRequests(
                        auth -> auth
                                // User endpoints — require a valid access token
                                .requestMatchers("/api/*/user/**")
                                .authenticated()
                                // Logout — requires a valid access token
                                .requestMatchers(HttpMethod.POST, "/api/*/auth/logout")
                                .authenticated()
                                // Everything else is public
                                .anyRequest()
                                .permitAll())
                // Run JWT filter before Spring Security's own
                // UsernamePasswordAuthenticationFilter so the
                // principal is set before authorization is checked
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Return JSON error bodies instead of Spring
                // Security's default HTML error pages
                .exceptionHandling(
                        ex -> ex
                                // 401 — no token or invalid/expired token
                                .authenticationEntryPoint(
                                        (req, res, e) -> {
                                            res.setStatus(401);
                                            res.setContentType(
                                                    MediaType.APPLICATION_JSON_VALUE);
                                            objectMapper.writeValue(
                                                    res.getWriter(),
                                                    new ErrorResponse(
                                                            401, "unauthorized", null));
                                        })
                                // 403 — authenticated but not authorized
                                .accessDeniedHandler(
                                        (req, res, e) -> {
                                            res.setStatus(403);
                                            res.setContentType(
                                                    MediaType.APPLICATION_JSON_VALUE);
                                            objectMapper.writeValue(
                                                    res.getWriter(),
                                                    new ErrorResponse(
                                                            403, "forbidden", null));
                                        }));

        return http.build();
    }

    /**
     * BCrypt password encoder with cost factor 12.
     *
     * <p>
     * Cost factor (log2 rounds): 2^12 = 4096 rounds per hash, ~200ms on modern
     * hardware. This makes brute-force attacks impractical — an attacker can only
     * try ~5 passwords/second instead of millions.
     *
     * <p>
     * Salt is generated automatically per password and embedded in the output
     * string — no separate salt storage needed. {@code encode()} produces a
     * different 60-char string each call even for the same input; {@code matches()}
     * extracts the embedded salt to re-verify.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
