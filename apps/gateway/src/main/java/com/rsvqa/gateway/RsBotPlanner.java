package com.rsvqa.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * RS-Bot's planning loop: the model decides which tools to call, this class
 * decides whether it may.
 *
 * <p>Spring AI's own tool execution is switched off deliberately. Owning the
 * loop is what makes the following enforceable rather than aspirational:
 *
 * <ul>
 *   <li>a step budget, a token budget and a wall-clock deadline;</li>
 *   <li>a per-session tool whitelist that rejects anything else, including tool
 *       names the model invented;</li>
 *   <li>one persisted {@code ToolInvocation} per call, including rejected and
 *       failed ones, so the trace shows what was attempted;</li>
 *   <li>cancellation between steps.</li>
 * </ul>
 *
 * <p>Tool results are facts. The model may summarise them but may not replace
 * them, and it never gets a tool that writes user data.
 */
@Service
public class RsBotPlanner {

    private static final Logger log = LoggerFactory.getLogger(RsBotPlanner.class);

    private final GeminiRelayAgentModel agentModel;
    private final RsBotProperties budgets;
    private final ObjectMapper objectMapper;

    public RsBotPlanner(
            GeminiRelayAgentModel agentModel,
            RsBotProperties budgets,
            ObjectMapper objectMapper
    ) {
        this.agentModel = agentModel;
        this.budgets = budgets;
        this.objectMapper = objectMapper;
    }

    public boolean available() {
        return agentModel.available();
    }

    /** One tool call as it actually happened, including refusals. */
    public record ExecutedTool(
            String name,
            String arguments,
            String output,
            String status,
            long latencyMs,
            String errorMessage
    ) {
    }

    public record PlanResult(
            String answer,
            List<ExecutedTool> toolCalls,
            int steps,
            String stopReason,
            String providerModel,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens
    ) {
    }

    public PlanResult plan(
            AgentDtos.AgentRequest request,
            ToolCallback[] catalogue,
            BooleanSupplier cancelled
    ) {
        Set<String> allowed = RsBotToolPolicy.allowedFor(request);
        Map<String, ToolCallback> callable = new LinkedHashMap<>();
        for (ToolCallback callback : catalogue) {
            String name = callback.getToolDefinition().name();
            if (allowed.contains(name)) {
                callable.put(name, callback);
            }
        }

        List<Message> messages = new ArrayList<>();
        messages.add(RsBotPrompt.system(callable.keySet(), budgets));
        messages.add(new UserMessage(request.message().trim() + contextSuffix(request)));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.copyOf(callable.values()))
                // RS-Bot executes tools itself; see the class comment.
                .internalToolExecutionEnabled(false)
                .build();

        List<ExecutedTool> executed = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(budgets.timeoutSeconds()).toNanos();
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        String stopReason = "completed";
        String answer = null;
        int step = 0;

        while (step < budgets.maxToolSteps()) {
            if (cancelled.getAsBoolean()) {
                stopReason = "cancelled";
                break;
            }
            if (System.nanoTime() > deadline) {
                stopReason = "timeout";
                break;
            }
            if (totalTokens > budgets.maxTotalTokens()) {
                stopReason = "token_budget_exhausted";
                break;
            }

            step++;
            ChatResponse response = agentModel.chatModel().call(new Prompt(messages, options));
            Usage usage = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens += orZero(usage.getPromptTokens());
                completionTokens += orZero(usage.getCompletionTokens());
                totalTokens += orZero(usage.getTotalTokens());
            }
            if (totalTokens > budgets.maxTotalTokens()) {
                stopReason = "token_budget_exhausted";
                break;
            }

            AssistantMessage assistant = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput();
            if (assistant == null) {
                stopReason = "empty_response";
                break;
            }
            if (!assistant.hasToolCalls()) {
                answer = assistant.getText();
                break;
            }

            messages.add(assistant);
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            if (calls.size() > budgets.maxToolCallsPerStep()) {
                calls = calls.subList(0, budgets.maxToolCallsPerStep());
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : calls) {
                ExecutedTool result = invoke(request, callable, call);
                executed.add(result);
                responses.add(new ToolResponseMessage.ToolResponse(
                        call.id(),
                        call.name(),
                        RsBotPrompt.fenceToolOutput(
                                call.name(),
                                "REJECTED".equals(result.status()) || "FAILED".equals(result.status())
                                        ? result.errorMessage()
                                        : result.output(),
                                budgets.maxToolOutputChars())));
            }
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }

        if (answer == null && "completed".equals(stopReason)) {
            stopReason = "max_steps_reached";
        }
        if (answer == null) {
            answer = fallbackAnswer(stopReason, executed);
        }

        log.info("rsBot model={} steps={} toolCalls={} stopReason={} totalTokens={}",
                agentModel.modelId(), step, executed.size(), stopReason, totalTokens);

        return new PlanResult(
                answer, List.copyOf(executed), step, stopReason, agentModel.modelId(),
                promptTokens == 0 ? null : promptTokens,
                completionTokens == 0 ? null : completionTokens,
                totalTokens == 0 ? null : totalTokens);
    }

    private ExecutedTool invoke(
            AgentDtos.AgentRequest request,
            Map<String, ToolCallback> callable,
            AssistantMessage.ToolCall call
    ) {
        String arguments = bindContextArguments(
                request,
                call.name(),
                call.arguments() == null ? "{}" : call.arguments()
        );
        ToolCallback callback = callable.get(call.name());
        if (callback == null) {
            // Either outside this session's whitelist or a name the model invented.
            // Both are refused, and the refusal is recorded rather than hidden.
            log.warn("rsBot rejected out-of-policy tool call name={}", call.name());
            return new ExecutedTool(call.name(), arguments, null, "REJECTED", 0,
                    "该工具不在本会话允许的工具列表中，已拒绝调用。");
        }
        long started = System.nanoTime();
        try {
            String output = callback.call(arguments);
            return new ExecutedTool(call.name(), arguments, output, "COMPLETED", millisSince(started), null);
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            log.warn("rsBot tool failed name={} detail={}", call.name(), message);
            // Handed back to the model as a tool result so it can recover or
            // explain, instead of aborting the whole run.
            return new ExecutedTool(call.name(), arguments, null, "FAILED", millisSince(started),
                    "工具调用失败：" + message);
        }
    }

    /**
     * Resource identifiers come from the authenticated session, never from the
     * model. The model may choose a permitted tool and its query, but it cannot
     * redirect that tool to another project, conversation or batch task.
     */
    private String bindContextArguments(
            AgentDtos.AgentRequest request,
            String toolName,
            String arguments
    ) {
        final JsonNode parsed;
        try {
            parsed = objectMapper.readTree(arguments);
        } catch (JsonProcessingException error) {
            return arguments;
        }
        if (!(parsed instanceof ObjectNode object)) {
            return arguments;
        }

        if (request.conversationId() != null && Set.of(
                "conversation_history",
                "conversation_vqa_results",
                "single_image_vqa"
        ).contains(toolName)) {
            object.put("conversationId", request.conversationId().toString());
        }
        if (request.projectId() != null && Set.of(
                "project_summary",
                "project_conversations",
                "project_vqa_statistics"
        ).contains(toolName)) {
            object.put("projectId", request.projectId().toString());
        }
        if (request.batchJobId() != null && Set.of(
                "batch_job_status",
                "batch_result_statistics"
        ).contains(toolName)) {
            object.put("batchJobId", request.batchJobId().toString());
        }
        if (Set.of(
                "confidence_distribution",
                "unsupported_question_summary",
                "failed_invocation_summary",
                "report_draft_data",
                "create_batch_plan"
        ).contains(toolName)) {
            if (request.projectId() != null) {
                object.put("scopeType", "project");
                object.put("scopeId", request.projectId().toString());
            } else if (request.batchJobId() != null) {
                object.put("scopeType", "batch");
                object.put("scopeId", request.batchJobId().toString());
            }
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("RS-Bot 工具参数无法绑定到当前会话。", error);
        }
    }

    /** Context the model needs but must not be able to invent. */
    private static String contextSuffix(AgentDtos.AgentRequest request) {
        StringBuilder suffix = new StringBuilder("\n\n[当前会话上下文]");
        if (request.projectId() != null) {
            suffix.append("\nprojectId=").append(request.projectId());
        }
        if (request.conversationId() != null) {
            suffix.append("\nconversationId=").append(request.conversationId());
        }
        if (request.batchJobId() != null) {
            suffix.append("\nbatchJobId=").append(request.batchJobId());
        }
        if (request.projectId() == null && request.conversationId() == null && request.batchJobId() == null) {
            suffix.append("\n未绑定具体项目、会话或批量任务。");
        }
        return suffix.toString();
    }

    private static String fallbackAnswer(String stopReason, List<ExecutedTool> executed) {
        String collected = executed.isEmpty()
                ? "本轮没有成功获取工具结果。"
                : "已调用工具：" + String.join("、", executed.stream().map(ExecutedTool::name).distinct().toList()) + "。";
        return switch (stopReason) {
            case "cancelled" -> "本轮分析已被取消。" + collected;
            case "timeout" -> "本轮分析超出时间预算，已停止。" + collected + "请缩小问题范围后重试。";
            case "token_budget_exhausted" -> "本轮分析超出 token 预算，已停止。" + collected + "请把问题拆分成更小的步骤。";
            case "max_steps_reached" -> "本轮已达到最大工具调用步数，未能得出结论。" + collected + "请把问题拆分成更小的步骤。";
            default -> "智能规划未返回可展示的结论。" + collected;
        };
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long millisSince(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    static Set<String> allowedToolNames(AgentDtos.AgentRequest request, ToolCallback[] catalogue) {
        Set<String> allowed = RsBotToolPolicy.allowedFor(request);
        return Arrays.stream(catalogue)
                .map(callback -> callback.getToolDefinition().name())
                .filter(allowed::contains)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
