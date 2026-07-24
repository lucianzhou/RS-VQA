package com.rsvqa.gateway;

import static com.rsvqa.gateway.AgentDtos.*;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public TrustedAgentController(
            TrustedAgentService agent,
            AgentToolRegistry tools,
            AgentStreamService stream
    ) {
        this.agent = agent;
        this.tools = tools;
        this.stream = stream;
    }

    @PostMapping("/runs")
    public AgentResponse run(@Valid @RequestBody AgentRequest request) {
        return agent.run(request);
    }

    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AgentRequest request) {
        return stream.run(request);
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
