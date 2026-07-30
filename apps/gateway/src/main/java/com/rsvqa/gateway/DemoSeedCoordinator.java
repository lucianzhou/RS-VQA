package com.rsvqa.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class DemoSeedCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedCoordinator.class);

    private final FileStorageService storage;
    private final DemoRuntimeInitializer runtimeInitializer;

    DemoSeedCoordinator(
            FileStorageService storage,
            DemoRuntimeInitializer runtimeInitializer
    ) {
        this.storage = storage;
        this.runtimeInitializer = runtimeInitializer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void initializeRuntimeOutputs(DemoSeedReadyEvent event) {
        for (String key : event.obsoleteStorageKeys()) {
            try {
                storage.deleteOwned(event.userId(), key);
            } catch (RequestValidationException error) {
                log.warn("demoCleanup rejected an out-of-namespace storage key");
            }
        }
        runtimeInitializer.process(event);
    }
}
