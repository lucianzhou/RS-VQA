package com.rsvqa.gateway;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiToolConfiguration {

    @Bean
    ToolCallbackProvider rsVqaReadOnlyTools(TrustedAgentTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
