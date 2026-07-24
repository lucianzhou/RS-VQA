package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp/client")
public class McpClientBoundaryController {

    private final McpClientBoundaryService mcp;

    public McpClientBoundaryController(McpClientBoundaryService mcp) {
        this.mcp = mcp;
    }

    @GetMapping("/status")
    public McpClientBoundaryService.McpClientStatus status() {
        return mcp.status();
    }

    @GetMapping("/tools")
    public List<McpClientBoundaryService.McpRemoteTool> tools() {
        return mcp.discoverTools();
    }

    @PostMapping("/tools/{toolName}")
    public McpClientBoundaryService.McpRemoteCallResult call(
            @PathVariable String toolName,
            @RequestBody(required = false) Map<String, Object> arguments
    ) {
        return mcp.call(toolName, arguments);
    }
}
