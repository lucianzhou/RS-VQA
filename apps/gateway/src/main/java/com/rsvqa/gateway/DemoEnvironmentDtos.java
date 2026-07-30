package com.rsvqa.gateway;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

final class DemoEnvironmentDtos {

    private DemoEnvironmentDtos() {
    }

    record ResetRequest(
            @NotBlank(message = "请输入演示环境重置确认短语。")
            String confirmation
    ) {
    }

    record ResetResponse(
            String state,
            UUID projectId,
            List<UUID> conversationIds,
            UUID batchJobId,
            List<UUID> agentSessionIds,
            int showcaseItems,
            List<String> notices
    ) {
    }
}
