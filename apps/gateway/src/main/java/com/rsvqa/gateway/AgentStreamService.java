package com.rsvqa.gateway;

import static com.rsvqa.gateway.AgentDtos.AgentRequest;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentStreamService {

    private final TrustedAgentService agent;
    private final Executor executor;

    public AgentStreamService(
            TrustedAgentService agent,
            @Qualifier("agentTaskExecutor") Executor executor
    ) {
        this.agent = agent;
        this.executor = executor;
    }

    public SseEmitter run(AgentRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String traceId = TraceId.current();
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        send(emitter, "accepted", Map.of("traceId", traceId, "state", "ACCEPTED"));
        executor.execute(() -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            MDC.put(TraceId.MDC_KEY, traceId);
            try {
                if (closed.get()) return;
                send(emitter, "tool_started", Map.of("traceId", traceId, "state", "TOOL_RUNNING"));
                // A disconnected client must stop the planning loop between steps
                // rather than paying for the remaining tool calls and tokens.
                var response = agent.run(request, closed::get);
                if (!closed.get()) {
                    send(emitter, "completed", response);
                    emitter.complete();
                }
            } catch (RuntimeException error) {
                if (!closed.get()) {
                    send(emitter, "failed", Map.of(
                            "traceId", traceId,
                            "code", "AGENT_RUN_FAILED",
                            "message", error.getMessage() == null ? "Agent 调用失败。" : error.getMessage()
                    ));
                    emitter.complete();
                }
            } finally {
                MDC.remove(TraceId.MDC_KEY);
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException error) {
            emitter.completeWithError(error);
        }
    }
}
