package com.backyard.playground.server.rest.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.backyard.playground.jwt.JwtTokenService;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Extracts the Bearer token from the Authorization header, validates it, and
 * sets the authentication in the SecurityContext. Requests without a valid
 * token are not rejected here — Spring Security's authorization rules handle
 * that for protected endpoints.
 */
@Profile("!home")
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String JTI_DENYLIST_PREFIX = "auth:jti:";

    private final JwtTokenService jwtTokenService;
    private final StringRedisTemplate redis;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, StringRedisTemplate redis) {
        this.jwtTokenService = jwtTokenService;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtTokenService.parseAccessToken(token);
                String jti = claims.getId();

                // Check jti denylist (populated on logout)
                if (Boolean.TRUE.equals(redis.hasKey(JTI_DENYLIST_PREFIX + jti))) {
                    // Token was explicitly revoked — skip auth
                    chain.doFilter(request, response);
                    return;
                }

                UUID userId = UUID.fromString(claims.getSubject());
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null,
                        List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid token — leave SecurityContext empty;
                // protected endpoints will return 401.
            }
        }

        chain.doFilter(request, response);
    }
}
