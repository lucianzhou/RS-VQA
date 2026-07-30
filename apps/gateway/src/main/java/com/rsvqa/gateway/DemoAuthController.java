package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;

@RestController
@RequestMapping("/api/v1")
public class DemoAuthController {

    private static final String DEMO_USERNAME = "local-demo";

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ConversationRepository conversationRepository;
    private final DemoAuthProperties properties;

    public DemoAuthController(
            UserRepository userRepository,
            ProjectRepository projectRepository,
            ConversationRepository conversationRepository,
            DemoAuthProperties properties
    ) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.conversationRepository = conversationRepository;
        this.properties = properties;
    }

    @PostMapping("/auth/demo")
    @Transactional
    public UserResponse demoLogin(HttpServletRequest request) {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()
                && !"anonymousUser".equals(SecurityContextHolder.getContext().getAuthentication().getName())) {
            return userRepository.findByUsername(SecurityContextHolder.getContext().getAuthentication().getName())
                    .map(UserResponse::from)
                    .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
        }
        UserEntity user = userRepository.findByUsername(DEMO_USERNAME)
                .orElseGet(() -> userRepository.save(new UserEntity(
                        DEMO_USERNAME,
                        "本地演示用户",
                        "USER",
                        true
                )));
        if (projectRepository.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(user.getId()).isEmpty()) {
            ProjectEntity project = projectRepository.save(new ProjectEntity(user, "城市土地利用"));
            conversationRepository.save(new ConversationEntity(project, "新分析"));
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user.getUsername(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        var session = request.getSession(false);
        if (session != null) {
            request.changeSessionId();
        } else {
            session = request.getSession(true);
        }
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );
        return UserResponse.from(user);
    }

    @GetMapping("/me")
    @Transactional
    public UserResponse me() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    public record UserResponse(
            java.util.UUID id,
            String username,
            String displayName,
            String role,
            boolean demo
    ) {
        static UserResponse from(UserEntity user) {
            return new UserResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getRole(),
                    user.isDemo()
            );
        }
    }
}
