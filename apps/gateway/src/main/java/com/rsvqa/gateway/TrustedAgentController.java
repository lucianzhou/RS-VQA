package com.rsvqa.gateway;

import static com.rsvqa.gateway.AgentDtos.*;
import static com.rsvqa.gateway.AgentActionDtos.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/agent")
public class TrustedAgentController {

    private final TrustedAgentService agent;
    private final AgentToolRegistry tools;
    private final AgentStreamService stream;
    private final AgentSessionService sessions;
    private final AgentActionService actions;

    public TrustedAgentController(
            TrustedAgentService agent,
            AgentToolRegistry tools,
            AgentStreamService stream,
            AgentSessionService sessions,
            AgentActionService actions
    ) {
        this.agent = agent;
        this.tools = tools;
        this.stream = stream;
        this.sessions = sessions;
        this.actions = actions;
    }

    @PostMapping("/runs")
    public AgentResponse run(@Valid @RequestBody AgentRequest request) {
        return agent.run(request);
    }

    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AgentRequest request) {
        return stream.run(request);
    }

    @GetMapping("/sessions")
    public List<AgentSessionSummary> sessions() {
        return sessions.list();
    }

    @PostMapping("/sessions")
    public AgentSessionDetail createSession(@Valid @RequestBody CreateAgentSessionRequest request) {
        return sessions.create(request);
    }

    @GetMapping("/sessions/{sessionId}")
    public AgentSessionDetail session(@PathVariable UUID sessionId) {
        return sessions.get(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void archiveSession(@PathVariable UUID sessionId) {
        sessions.archive(sessionId);
    }

    @GetMapping("/actions")
    public List<ActionProposalResponse> actions(@RequestParam(required = false) UUID sessionId) {
        return actions.list(sessionId);
    }

    @PostMapping("/actions")
    public ActionProposalResponse proposeAction(@Valid @RequestBody CreateProposalRequest request) {
        return actions.propose(request);
    }

    @PostMapping("/actions/{proposalId}/confirm")
    public ActionProposalResponse confirmAction(@PathVariable UUID proposalId) {
        return actions.confirm(proposalId);
    }

    @PostMapping("/actions/{proposalId}/reject")
    public ActionProposalResponse rejectAction(@PathVariable UUID proposalId) {
        return actions.reject(proposalId);
    }

    @GetMapping("/tools")
    public List<Map<String, String>> toolCatalog() {
        return java.util.Arrays.stream(tools.callbacks())
                .map(ToolCallback::getToolDefinition)
                .map(definition -> Map.of(
                        "name", definition.name(),
                        "description", definition.description(),
                        "inputSchema", definition.inputSchema()
                ))
                .toList();
    }
}
