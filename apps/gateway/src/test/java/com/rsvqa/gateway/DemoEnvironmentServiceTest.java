package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentSessionRepository;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.BatchItemRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ImageAssetRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class DemoEnvironmentServiceTest {

    @Mock DemoEnvironmentProperties properties;
    @Mock DemoShowcaseCatalog catalog;
    @Mock DemoEnvironmentStore store;
    @Mock UserRepository users;
    @Mock ProjectRepository projects;
    @Mock ConversationRepository conversations;
    @Mock ImageAssetRepository images;
    @Mock BatchJobRepository batchJobs;
    @Mock BatchItemRepository batchItems;
    @Mock AgentSessionRepository agentSessions;
    @Mock AuditEventRepository auditEvents;
    @Mock FileStorageService storage;
    @Mock VqaService vqa;
    @Mock ApplicationEventPublisher events;

    @InjectMocks
    DemoEnvironmentService environment;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyTheNamedDemoPrincipalCanReset() {
        given(properties.enabled()).willReturn(true);
        authenticate("another-user");

        assertThatThrownBy(() -> environment.reset(DemoEnvironmentService.CONFIRMATION))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(store, catalog);
    }

    @Test
    void aDemoFlagOnAnotherAccountDoesNotGrantResetAuthority() {
        given(properties.enabled()).willReturn(true);
        authenticate("demo-copy");

        assertThatThrownBy(() -> environment.reset(DemoEnvironmentService.CONFIRMATION))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(store, catalog);
    }

    @Test
    void theExactConfirmationPhraseIsRequiredBeforeReadingOrDeletingData() {
        given(properties.enabled()).willReturn(true);
        authenticate(DemoEnvironmentService.DEMO_USERNAME);
        given(users.findByUsername(DemoEnvironmentService.DEMO_USERNAME)).willReturn(Optional.of(
                new UserEntity(DemoEnvironmentService.DEMO_USERNAME, "Local Demo", "USER", true)
        ));

        assertThatThrownBy(() -> environment.reset("reset"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining(DemoEnvironmentService.CONFIRMATION);
        verifyNoInteractions(store, catalog);
    }

    @Test
    void mockRuntimeIsRejectedBeforeReadingOrDeletingDemoData() {
        given(properties.enabled()).willReturn(true);
        given(properties.sourceRoot()).willReturn("/unused");
        given(properties.modelReleaseId()).willReturn("approved-release");
        authenticate(DemoEnvironmentService.DEMO_USERNAME);
        given(users.findByUsername(DemoEnvironmentService.DEMO_USERNAME)).willReturn(Optional.of(
                new UserEntity(DemoEnvironmentService.DEMO_USERNAME, "Local Demo", "USER", true)
        ));
        given(vqa.currentModel()).willReturn(new RuntimeModelInfoResponse(
                "mock",
                true,
                "mock-release",
                "1.0",
                "demo",
                "predicted_soft",
                "mock_demo",
                List.of(),
                Map.of()
        ));

        assertThatThrownBy(() -> environment.reset(DemoEnvironmentService.CONFIRMATION))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("Mock");
        verifyNoInteractions(store, catalog);
    }

    @Test
    void mismatchedRealReleaseIsRejectedBeforeReadingOrDeletingDemoData() {
        given(properties.enabled()).willReturn(true);
        given(properties.sourceRoot()).willReturn("/unused");
        given(properties.modelReleaseId()).willReturn("approved-release");
        authenticate(DemoEnvironmentService.DEMO_USERNAME);
        given(users.findByUsername(DemoEnvironmentService.DEMO_USERNAME)).willReturn(Optional.of(
                new UserEntity(DemoEnvironmentService.DEMO_USERNAME, "Local Demo", "USER", true)
        ));
        given(vqa.currentModel()).willReturn(new RuntimeModelInfoResponse(
                "real",
                true,
                "different-release",
                "1.0",
                "rsvqa_hr_grouped_answer_classification",
                "predicted_soft",
                "research_vilt_predicted_soft",
                List.of(),
                Map.of()
        ));

        assertThatThrownBy(() -> environment.reset(DemoEnvironmentService.CONFIRMATION))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("发布身份不匹配");
        verifyNoInteractions(store, catalog);
    }

    private static void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
    }
}
