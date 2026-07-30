package com.rsvqa.gateway;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "rsvqa.batch",
        name = "recovery-enabled",
        havingValue = "true",
        matchIfMissing = true
)
class BatchRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BatchRecoveryCoordinator.class);

    private final BatchService batches;
    private final BatchWorker worker;

    BatchRecoveryCoordinator(BatchService batches, BatchWorker worker) {
        this.batches = batches;
        this.worker = worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverOnStartup() {
        recoverAndResume("startup");
    }

    @Scheduled(fixedDelayString = "${rsvqa.batch.recovery-interval:PT30S}")
    void recoverPeriodically() {
        recoverAndResume("scheduled");
    }

    private void recoverAndResume(String trigger) {
        Set<UUID> recovered = batches.recoverExpiredLeases();
        Set<UUID> runnable = new LinkedHashSet<>(batches.runnableJobIds());
        runnable.forEach(worker::process);
        if (!recovered.isEmpty() || !runnable.isEmpty()) {
            log.info(
                    "batchRecovery trigger={} recoveredItemsForJobs={} runnableJobs={}",
                    trigger,
                    recovered.size(),
                    runnable.size()
            );
        }
    }
}
