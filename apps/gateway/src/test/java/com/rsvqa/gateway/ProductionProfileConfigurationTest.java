package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ProductionProfileConfigurationTest {

    @Test
    void productionProfileClosesDemoDocumentationAndNonHealthActuators() throws IOException {
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-production.yaml"))
                .getFirst();

        assertThat(source.getProperty("server.servlet.session.cookie.secure")).isEqualTo(true);
        assertThat(source.getProperty("server.servlet.session.cookie.http-only")).isEqualTo(true);
        assertThat(source.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(source.getProperty("rsvqa.demo-auth.enabled")).isEqualTo(false);
        assertThat(source.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(source.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
        assertThat(source.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
}
