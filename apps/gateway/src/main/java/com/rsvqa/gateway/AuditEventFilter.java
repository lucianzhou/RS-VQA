package com.rsvqa.gateway;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class AuditEventFilter extends OncePerRequestFilter {

    private final ObjectProvider<UserRepository> users;
    private final ObjectProvider<AuditEventRepository> events;

    public AuditEventFilter(
            ObjectProvider<UserRepository> users,
            ObjectProvider<AuditEventRepository> events
    ) {
        this.users = users;
        this.events = events;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) && !"PUT".equals(request.getMethod())
                && !"PATCH".equals(request.getMethod()) && !"DELETE".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().startsWith("/api/")
                    && SecurityContextHolder.getContext().getAuthentication() != null) {
                String username = SecurityContextHolder.getContext().getAuthentication().getName();
                try {
                    UserRepository userRepository = users.getIfAvailable();
                    AuditEventRepository eventRepository = events.getIfAvailable();
                    if (userRepository != null && eventRepository != null) {
                        var user = userRepository.findByUsername(username).orElse(null);
                        String path = request.getRequestURI().replaceAll("[\\r\\n]", "");
                        eventRepository.save(new AuditEventEntity(
                                user,
                                request.getMethod() + " " + path,
                                "HTTP_REQUEST",
                                null,
                                TraceId.current(),
                                response.getStatus() < 400 ? "SUCCESS" : "FAILURE",
                                "status=" + response.getStatus()
                        ));
                    }
                } catch (RuntimeException ignored) {
                    // Audit persistence must not replace the original API response.
                }
            }
        }
    }
}
