package com.rsvqa.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.ObjectProvider;

import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class McpBearerTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final McpSecurityProperties properties;
    private final ObjectProvider<UserRepository> users;
    private final ObjectProvider<AuditEventRepository> auditEvents;

    McpBearerTokenFilter(
            McpSecurityProperties properties,
            ObjectProvider<UserRepository> users,
            ObjectProvider<AuditEventRepository> auditEvents
    ) {
        this.properties = properties;
        this.users = users;
        this.auditEvents = auditEvents;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/mcp".equals(path) || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean authenticated = false;
        try {
            if (!properties.enabled()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            byte[] supplied = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
            byte[] expected = properties.bearerToken().getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(supplied, expected)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    properties.principal(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_MCP"))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            authenticated = true;
            filterChain.doFilter(request, response);
        } finally {
            audit(request, response, authenticated);
            SecurityContextHolder.clearContext();
        }
    }

    private void audit(HttpServletRequest request, HttpServletResponse response, boolean authenticated) {
        try {
            UserRepository userRepository = users.getIfAvailable();
            AuditEventRepository eventRepository = auditEvents.getIfAvailable();
            if (eventRepository == null) {
                return;
            }
            var user = !authenticated || userRepository == null
                    ? null
                    : userRepository.findByUsername(properties.principal()).orElse(null);
            eventRepository.save(new AuditEventEntity(
                    user,
                    request.getMethod() + " /mcp",
                    "MCP_REQUEST",
                    null,
                    TraceId.current(),
                    response.getStatus() < 400 ? "SUCCESS" : "FAILURE",
                    "status=" + response.getStatus()
            ));
        } catch (RuntimeException ignored) {
            // MCP audit persistence must not replace the protocol response.
        }
    }
}
