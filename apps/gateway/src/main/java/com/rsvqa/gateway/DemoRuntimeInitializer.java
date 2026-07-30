package com.rsvqa.gateway;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
class DemoRuntimeInitializer {

    private final DemoConversationSeeder conversationSeeder;
    private final BatchWorker batchWorker;

    DemoRuntimeInitializer(
            DemoConversationSeeder conversationSeeder,
            BatchWorker batchWorker
    ) {
        this.conversationSeeder = conversationSeeder;
        this.batchWorker = batchWorker;
    }

    @Async("batchTaskExecutor")
    public void process(DemoSeedReadyEvent event) {
        conversationSeeder.process(
                event.userId(),
                event.conversations(),
                event.modelReleaseId()
        );
        batchWorker.process(event.batchJobId());
    }
}
