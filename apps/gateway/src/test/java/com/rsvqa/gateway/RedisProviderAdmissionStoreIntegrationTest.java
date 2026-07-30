package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "RSVQA_REDIS_INTEGRATION", matches = "true")
class RedisProviderAdmissionStoreIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static UUID userId;
    private static RedisProviderAdmissionStore store;

    @BeforeAll
    static void connect() {
        int port = Integer.parseInt(System.getenv().getOrDefault("RSVQA_REDIS_PORT", "16379"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        userId = UUID.randomUUID();
        store = new RedisProviderAdmissionStore(
                redis,
                Clock.fixed(Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    @AfterAll
    static void disconnect() {
        Set<String> keys = redis.keys("rsvqa:provider:" + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        connectionFactory.destroy();
    }

    @Test
    void enforcesConcurrencyWithExpiringLeases() {
        ProviderPolicyProperties.Limits limits =
                new ProviderPolicyProperties.Limits(10, 1, 1, 10_000, 100);
        ProviderAdmissionStore.Admission first = store.acquire(
                userId, "concurrency", ProviderWorkload.VISION, 1, 100, limits);

        assertThatThrownBy(() -> store.acquire(
                userId, "concurrency", ProviderWorkload.VISION, 1, 100, limits))
                .isInstanceOf(ProviderAdmissionException.class)
                .hasMessageContaining("并发");

        first.complete(50);
        store.acquire(userId, "concurrency", ProviderWorkload.VISION, 1, 100, limits)
                .complete(50);
    }

    @Test
    void enforcesMinuteRateAndDailyTokenReservation() {
        ProviderPolicyProperties.Limits rate =
                new ProviderPolicyProperties.Limits(2, 2, 1, 10_000, 100);
        store.acquire(userId, "rate", ProviderWorkload.VISION, 1, 100, rate).complete(0);
        store.acquire(userId, "rate", ProviderWorkload.VISION, 1, 100, rate).complete(0);
        assertThatThrownBy(() -> store.acquire(
                userId, "rate", ProviderWorkload.VISION, 1, 100, rate))
                .isInstanceOf(ProviderAdmissionException.class)
                .hasMessageContaining("频繁");

        ProviderPolicyProperties.Limits budget =
                new ProviderPolicyProperties.Limits(10, 2, 1, 1_000, 600);
        store.acquire(userId, "budget", ProviderWorkload.AGENT, 1, 600, budget).complete(600);
        assertThatThrownBy(() -> store.acquire(
                userId, "budget", ProviderWorkload.AGENT, 1, 600, budget))
                .isInstanceOf(ProviderAdmissionException.class)
                .hasMessageContaining("预算");
    }
}
