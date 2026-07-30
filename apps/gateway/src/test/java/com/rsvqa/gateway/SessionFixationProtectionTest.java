package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

class SessionFixationProtectionTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualLoginRotatesAnExistingSessionId() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserEntity user = new UserEntity("analyst", "encoded", "Analyst", "USER", false);
        given(users.findByUsername("analyst")).willReturn(Optional.of(user));
        given(passwords.matches("correct-password", "encoded")).willReturn(true);
        LocalAuthController controller = new LocalAuthController(
                users,
                mock(ProjectRepository.class),
                mock(ConversationRepository.class),
                passwords
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        String sessionIdBeforeLogin = request.getSession(true).getId();

        controller.login(new LocalAuthController.LoginRequest("analyst", "correct-password"), request);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getId()).isNotEqualTo(sessionIdBeforeLogin);
    }
}
