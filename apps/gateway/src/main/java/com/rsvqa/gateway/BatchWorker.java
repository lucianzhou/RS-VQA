package com.rsvqa.gateway;

import java.util.Optional;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class BatchWorker {

    private final BatchService batches;
    private final VqaService vqa;

    public BatchWorker(BatchService batches, VqaService vqa) {
        this.batches = batches;
        this.vqa = vqa;
    }

    @Async("batchTaskExecutor")
    public void process(UUID jobId) {
        batches.begin(jobId);
        while (true) {
            Optional<BatchService.BatchWorkItem> next = batches.claimNext(jobId);
            if (next.isEmpty()) {
                return;
            }
            BatchService.BatchWorkItem item = next.get();
            try {
                ApiPredictionResponse result = vqa.answer(
                        batches.read(item.storageKey()),
                        item.filename(),
                        item.contentType(),
                        item.question(),
                        item.modelReleaseId()
                );
                batches.succeed(jobId, item.id(), result);
            } catch (RuntimeException error) {
                batches.fail(jobId, item.id(), error);
            }
        }
    }
}
