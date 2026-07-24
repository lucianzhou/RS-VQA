package com.rsvqa.gateway;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/auth")
public class LocalAuthController {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ConversationRepository conversations;
    private final PasswordEncoder passwords;

    public LocalAuthController(
            UserRepository users,
            ProjectRepository projects,
            ConversationRepository conversations,
            PasswordEncoder passwords
    ) {
        this.users = users;
        this.projects = projects;
        this.conversations = conversations;
        this.passwords = passwords;
    }

    @PostMapping("/register")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public DemoAuthController.UserResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        String username = request.username().trim().toLowerCase();
        if (users.findByUsername(username).isPresent()) {
            throw new RequestValidationException("用户名已存在。");
        }
        UserEntity user = users.save(new UserEntity(
                username,
                passwords.encode(request.password()),
                request.displayName().trim(),
                "USER",
                false
        ));
        ProjectEntity project = projects.save(new ProjectEntity(user, "我的遥感分析"));
        conversations.save(new ConversationEntity(project, "新分析"));
        authenticate(user, servletRequest);
        return DemoAuthController.UserResponse.from(user);
    }

    @PostMapping("/login")
    @Transactional
    public DemoAuthController.UserResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        UserEntity user = users.findByUsername(request.username().trim().toLowerCase())
                .orElseThrow(() -> new RequestValidationException("用户名或密码不正确。"));
        if (user.getPasswordHash() == null || !passwords.matches(request.password(), user.getPasswordHash())) {
            throw new RequestValidationException("用户名或密码不正确。");
        }
        authenticate(user, servletRequest);
        return DemoAuthController.UserResponse.from(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(UserEntity user, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user.getUsername(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }

    record RegisterRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9._-]{3,40}", message = "用户名需为 3–40 位字母、数字或 ._-。")
            String username,
            @NotBlank @Size(min = 10, max = 100, message = "密码需为 10–100 个字符。") String password,
            @NotBlank @Size(max = 120) String displayName
    ) {
    }

    record LoginRequest(
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(max = 100) String password
    ) {
    }
}
