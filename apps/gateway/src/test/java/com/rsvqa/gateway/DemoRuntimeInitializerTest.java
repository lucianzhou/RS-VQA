package com.rsvqa.gateway;

import static org.mockito.Mockito.inOrder;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DemoRuntimeInitializerTest {

    @Mock DemoConversationSeeder conversationSeeder;
    @Mock BatchWorker batchWorker;

    @InjectMocks
    DemoRuntimeInitializer initializer;

    @Test
    void completesConversationSeedsBeforeStartingTheBatchWorker() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID batchJobId = UUID.randomUUID();
        List<DemoSeedReadyEvent.ConversationSeed> conversations = List.of(
                new DemoSeedReadyEvent.ConversationSeed(
                        conversationId,
                        List.of("Are there roads in the image?")
                )
        );
        DemoSeedReadyEvent event = new DemoSeedReadyEvent(
                userId,
                List.of(),
                batchJobId,
                "approved-release",
                conversations
        );

        initializer.process(event);

        InOrder order = inOrder(conversationSeeder, batchWorker);
        order.verify(conversationSeeder).process(userId, conversations, "approved-release");
        order.verify(batchWorker).process(batchJobId);
        order.verifyNoMoreInteractions();
    }
}
