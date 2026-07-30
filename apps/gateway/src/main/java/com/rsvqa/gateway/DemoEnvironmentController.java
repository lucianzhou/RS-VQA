package com.rsvqa.gateway;

import static com.rsvqa.gateway.DemoEnvironmentDtos.*;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/demo-environment")
public class DemoEnvironmentController {

    private final DemoEnvironmentService environment;

    public DemoEnvironmentController(DemoEnvironmentService environment) {
        this.environment = environment;
    }

    @PostMapping("/reset")
    public ResetResponse reset(@Valid @RequestBody ResetRequest request) {
        return environment.reset(request.confirmation());
    }
}
