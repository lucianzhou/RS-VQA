package com.rsvqa.gateway;

import java.util.List;
import java.util.UUID;

record DemoSeedReadyEvent(
        UUID userId,
        List<String> obsoleteStorageKeys,
        UUID batchJobId,
        String modelReleaseId,
        List<ConversationSeed> conversations
) {
    record ConversationSeed(UUID conversationId, List<String> questions) {
    }
}
