package com.rsvqa.gateway;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiError> invalidRequest(RequestValidationException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_REQUEST", error.getMessage(), false));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException error) {
        Map<String, Object> details = Map.of(
                "fields",
                error.getBindingResult().getFieldErrors().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                field -> field.getField(),
                                field -> field.getDefaultMessage() == null ? "字段无效。" : field.getDefaultMessage(),
                                (first, ignored) -> first
                        ))
        );
        ApiError body = new ApiError(
                "VALIDATION_ERROR",
                "请求字段校验失败。",
                TraceId.current(),
                java.time.Instant.now(),
                details,
                false
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", error.getMessage(), false));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> fileTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("FILE_TOO_LARGE", "图像文件不能超过 10 MiB。", false));
    }

    @ExceptionHandler(ModelServiceException.class)
    ResponseEntity<ApiError> modelServiceFailure(ModelServiceException error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("MODEL_SERVICE_UNAVAILABLE", error.getMessage(), true));
    }

    @ExceptionHandler(KnowledgeServiceException.class)
    ResponseEntity<ApiError> knowledgeServiceFailure(KnowledgeServiceException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("RAG_UNAVAILABLE", error.getMessage(), true));
    }

    @ExceptionHandler(ProviderNotConfiguredException.class)
    ResponseEntity<ApiError> providerNotConfigured(ProviderNotConfiguredException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("PROVIDER_NOT_CONFIGURED", error.getMessage(), false));
    }

    @ExceptionHandler(McpClientBoundaryException.class)
    ResponseEntity<ApiError> mcpToolFailure(McpClientBoundaryException error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("MCP_TOOL_FAILED", error.getMessage(), true));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "无权访问该资源。", false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception error) {
        log.error("Unexpected error caught by fallback handler", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "服务暂时无法完成请求，请稍后重试。", true));
    }
}
