package com.rsvqa.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class McpSecurityTestController {

    @PostMapping("/mcp")
    ResponseEntity<Void> initialize() {
        return ResponseEntity.noContent().build();
    }
}
