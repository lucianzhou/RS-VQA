package com.rsvqa.gateway;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

@Service
class DemoConversationSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoConversationSeeder.class);

    private final WorkspaceService workspace;
    private final UserRepository users;
    private final AuditEventRepository auditEvents;

    DemoConversationSeeder(
            WorkspaceService workspace,
            UserRepository users,
            AuditEventRepository auditEvents
    ) {
        this.workspace = workspace;
        this.users = users;
        this.auditEvents = auditEvents;
    }

    public void process(
            UUID userId,
            List<DemoSeedReadyEvent.ConversationSeed> conversations,
            String modelReleaseId
    ) {
        int completed = 0;
        int failed = 0;
        for (DemoSeedReadyEvent.ConversationSeed conversation : conversations) {
            for (String question : conversation.questions()) {
                try {
                    workspace.askResearchForUser(
                            userId,
                            conversation.conversationId(),
                            question,
                            modelReleaseId
                    );
                    completed++;
                } catch (ResourceNotFoundException error) {
                    log.info("demoConversationSeed stopped because the demo generation was replaced");
                    return;
                } catch (RuntimeException error) {
                    failed++;
                    log.warn(
                            "demoConversationSeed failed conversationId={} errorType={}",
                            conversation.conversationId(),
                            error.getClass().getSimpleName()
                    );
                }
            }
        }
        recordOutcome(userId, completed, failed);
    }

    private void recordOutcome(UUID userId, int completed, int failed) {
        users.findById(userId).ifPresent(user -> auditEvents.save(new AuditEventEntity(
                user,
                "DEMO_CONVERSATION_SEED",
                "DEMO_ENVIRONMENT",
                null,
                TraceId.current(),
                failed == 0 ? "SUCCESS" : "PARTIAL",
                "runtime_completed=" + completed + ",runtime_failed=" + failed
        )));
    }
}
