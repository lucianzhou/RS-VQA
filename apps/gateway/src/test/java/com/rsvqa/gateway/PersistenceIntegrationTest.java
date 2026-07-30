package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.data.redis.repositories.enabled=false",
        "rsvqa.demo-auth.enabled=true",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/rsvqa",
        "spring.datasource.username=rsvqa",
        "spring.datasource.password=rsvqa_dev_only",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=16379"
})
@EnabledIfEnvironmentVariable(named = "RSVQA_COMPOSE_INTEGRATION", matches = "true")
class PersistenceIntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    UserRepository users;

    @Autowired
    ProjectRepository projects;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void appliesFlywayPersistsOwnedDataAndUsesRedisAsCache() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");

        String suffix = UUID.randomUUID().toString();
        UserEntity user = users.save(new UserEntity("integration-" + suffix, "Integration", "USER", false));
        projects.save(new ProjectEntity(user, "集成测试项目"));

        assertThat(projects.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(user.getId()))
                .extracting(ProjectEntity::getName)
                .containsExactly("集成测试项目");

        redis.opsForValue().set("rsvqa:test:" + suffix, "ok");
        assertThat(redis.opsForValue().get("rsvqa:test:" + suffix)).isEqualTo("ok");
    }
}
