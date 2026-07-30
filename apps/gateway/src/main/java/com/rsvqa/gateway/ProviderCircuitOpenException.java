package com.rsvqa.gateway;

public class ProviderCircuitOpenException extends RuntimeException {

    private final long retryAfterSeconds;

    ProviderCircuitOpenException(long retryAfterSeconds) {
        super("该模型的上游服务连续失败，系统已暂停新的调用，请稍后重试。");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
