package com.rsvqa.gateway;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * Classifies relay HTTP failures by status code and keeps credentials out of
 * the fallout.
 *
 * <p>Two reasons this exists instead of the framework default:
 *
 * <ol>
 *   <li><b>Retry correctness.</b> 408, 429 and 5xx are worth another attempt;
 *       every other 4xx (bad key, unknown model, model cannot accept images) is
 *       not, and retrying one only multiplies the failure. The mapping is
 *       written out here rather than inherited so it cannot drift.</li>
 *   <li><b>Secret hygiene.</b> A relay can echo the submitted key back inside
 *       its error body. That body must never reach a log line, an exception
 *       message or a user.</li>
 * </ol>
 */
final class RelayResponseErrorHandler implements ResponseErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(RelayResponseErrorHandler.class);

    private final String providerId;

    RelayResponseErrorHandler(String providerId) {
        this.providerId = providerId;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        // A third-party body can contain credentials or internal upstream
        // details in encodings that string replacement cannot safely redact.
        log.warn("provider={} status={} failureType={}",
                providerId, status, transient_(status) ? "transient" : "non_transient");

        String message = "中转站返回 HTTP " + status + "。";
        if (transient_(status)) {
            throw new TransientAiException(message);
        }
        throw new NonTransientAiException(message + reasonFor(status));
    }

    private static boolean transient_(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    /** Short, credential-free hint. Never derived from the relay's response body. */
    private static String reasonFor(int status) {
        return switch (status) {
            case 401, 403 -> "服务端密钥无效或无权访问该模型。";
            case 404 -> "中转站没有该模型或该接口路径。";
            case 400, 422 -> "请求被中转站拒绝；该模型可能不接受当前输入形式（例如图像）。";
            case 413 -> "请求体超出中转站限制。";
            default -> "该错误不会自动重试。";
        };
    }

}
