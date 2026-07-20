package com.rsvqa.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiError> invalidRequest(RequestValidationException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("invalid_request", error.getMessage()));
    }

    @ExceptionHandler(ModelServiceException.class)
    ResponseEntity<ApiError> modelServiceFailure(ModelServiceException error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("model_service_unavailable", error.getMessage()));
    }
}
