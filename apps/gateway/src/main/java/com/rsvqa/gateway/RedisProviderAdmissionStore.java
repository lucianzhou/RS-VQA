package com.rsvqa.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class RedisProviderAdmissionStore implements ProviderAdmissionStore {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    private static final DefaultRedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local requests = redis.call('INCR', KEYS[1])
            if requests == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if requests > tonumber(ARGV[2]) then
              return {1, redis.call('TTL', KEYS[1])}
            end

            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[3])
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then
              return {2, 5}
            end

            local used = tonumber(redis.call('GET', KEYS[3]) or '0')
            if used + tonumber(ARGV[5]) > tonumber(ARGV[6]) then
              return {3, tonumber(ARGV[7])}
            end

            redis.call('ZADD', KEYS[2], ARGV[9], ARGV[8])
            redis.call('PEXPIRE', KEYS[2], ARGV[10])
            redis.call('INCRBY', KEYS[3], ARGV[5])
            redis.call('EXPIRE', KEYS[3], ARGV[7])
            return {0, 0}
            """, List.class);

    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            redis.call('ZREM', KEYS[1], ARGV[1])
            local current = tonumber(redis.call('GET', KEYS[2]) or '0')
            local adjusted = current - tonumber(ARGV[2]) + tonumber(ARGV[3])
            if adjusted < 0 then adjusted = 0 end
            redis.call('SET', KEYS[2], adjusted, 'EX', ARGV[4])
            return adjusted
            """, Long.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final StringRedisTemplate fixedRedis;
    private final Clock clock;

    @Autowired
    RedisProviderAdmissionStore(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
        this.fixedRedis = null;
        this.clock = Clock.systemUTC();
    }

    RedisProviderAdmissionStore(StringRedisTemplate redis, Clock clock) {
        this.redisProvider = null;
        this.fixedRedis = redis;
        this.clock = clock;
    }

    @Override
    public Admission acquire(
            UUID userId,
            String providerId,
            ProviderWorkload workload,
            int units,
            int tokenReservation,
            ProviderPolicyProperties.Limits limits
    ) {
        if (units < 1 || units > limits.maxItemsPerRequest()) {
            throw new ProviderAdmissionException(
                    "BATCH_SIZE",
                    "该模型单次最多处理 " + limits.maxItemsPerRequest() + " 个输入项。",
                    60
            );
        }
        Instant now = clock.instant();
        String prefix = "rsvqa:provider:" + userId + ":" + safe(providerId) + ":"
                + workload.name().toLowerCase(java.util.Locale.ROOT);
        String rateKey = prefix + ":rate:" + now.getEpochSecond() / 60;
        String leaseKey = prefix + ":leases";
        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String budgetKey = prefix + ":tokens:" + date;
        String leaseId = UUID.randomUUID().toString();
        long budgetTtl = Math.max(
                60,
                Duration.between(now, date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)).getSeconds() + 3600
        );
        int reserved = Math.max(0, tokenReservation);

        List<?> response;
        try {
            StringRedisTemplate redis = redis();
            response = redis.execute(
                    ACQUIRE,
                    List.of(rateKey, leaseKey, budgetKey),
                    "120",
                    Integer.toString(limits.requestsPerMinute()),
                    Long.toString(now.toEpochMilli()),
                    Integer.toString(limits.maxConcurrent()),
                    Integer.toString(reserved),
                    Long.toString(limits.dailyTokenBudget()),
                    Long.toString(budgetTtl),
                    leaseId,
                    Long.toString(now.plus(LEASE_DURATION).toEpochMilli()),
                    Long.toString(LEASE_DURATION.toMillis())
            );
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
        int code = response == null || response.isEmpty() ? -1 : integer(response.get(0));
        long retryAfter = response == null || response.size() < 2 ? 60 : Math.max(1, integer(response.get(1)));
        if (code != 0) {
            throw switch (code) {
                case 1 -> new ProviderAdmissionException(
                        "RATE_LIMIT", "该模型请求过于频繁，请稍后重试。", retryAfter);
                case 2 -> new ProviderAdmissionException(
                        "CONCURRENCY_LIMIT", "该模型当前并发任务已满，请等待正在执行的任务完成。", retryAfter);
                case 3 -> new ProviderAdmissionException(
                        "TOKEN_BUDGET", "该模型今日 token 预算已用尽，请明日再试或联系管理员调整预算。", retryAfter);
                default -> new ProviderAdmissionException(
                        "POLICY_UNAVAILABLE", "模型调用准入状态不可用，系统已拒绝向外部服务发送数据。", 30);
            };
        }
        return new RedisAdmission(leaseKey, budgetKey, leaseId, reserved, budgetTtl);
    }

    private final class RedisAdmission implements Admission {
        private final String leaseKey;
        private final String budgetKey;
        private final String leaseId;
        private final int reservation;
        private final long budgetTtl;
        private final AtomicBoolean completed = new AtomicBoolean();

        private RedisAdmission(
                String leaseKey,
                String budgetKey,
                String leaseId,
                int reservation,
                long budgetTtl
        ) {
            this.leaseKey = leaseKey;
            this.budgetKey = budgetKey;
            this.leaseId = leaseId;
            this.reservation = reservation;
            this.budgetTtl = budgetTtl;
        }

        @Override
        public void complete(Integer actualTokens) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            int charged = actualTokens == null ? reservation : Math.max(0, actualTokens);
            try {
                StringRedisTemplate redis = redis();
                redis.execute(
                        COMPLETE,
                        List.of(leaseKey, budgetKey),
                        leaseId,
                        Integer.toString(reservation),
                        Integer.toString(charged),
                        Long.toString(budgetTtl)
                );
            } catch (RuntimeException error) {
                throw unavailable(error);
            }
        }
    }

    private StringRedisTemplate redis() {
        StringRedisTemplate redis = fixedRedis != null
                ? fixedRedis
                : redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            throw new ProviderAdmissionException(
                    "POLICY_UNAVAILABLE",
                    "模型调用准入状态不可用，系统已拒绝向外部服务发送数据。",
                    30
            );
        }
        return redis;
    }

    private static ProviderAdmissionException unavailable(RuntimeException error) {
        String type = error instanceof RedisConnectionFailureException
                ? "Redis connection failure"
                : error.getClass().getSimpleName();
        ProviderAdmissionException wrapped = new ProviderAdmissionException(
                "POLICY_UNAVAILABLE",
                "模型调用准入状态不可用，系统已拒绝向外部服务发送数据。(" + type + ")",
                30
        );
        wrapped.initCause(error);
        return wrapped;
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_");
    }
}
