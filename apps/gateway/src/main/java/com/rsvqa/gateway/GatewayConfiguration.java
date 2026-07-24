package com.rsvqa.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GatewayConfiguration {

    @Bean
    WebClient modelServiceClient(ModelServiceProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    @Bean
    WebClient knowledgeServiceClient(KnowledgeServiceProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }
}
