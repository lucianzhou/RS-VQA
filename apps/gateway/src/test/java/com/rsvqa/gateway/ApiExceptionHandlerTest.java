package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void disabledEndpointKeepsItsNotFoundStatus() {
        var response = handler.responseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("HTTP_404");
    }

    @Test
    void missingStaticResourceIsNotReportedAsInternalFailure() {
        var response = handler.noResource();
        var error = new NoResourceFoundException(HttpMethod.GET, "/v3/api-docs");

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void providerBudgetRejectionIs429WithRetryMetadata() {
        var response = handler.providerAdmissionRejected(new ProviderAdmissionException(
                "TOKEN_BUDGET", "今日预算已用尽。", 3600));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PROVIDER_TOKEN_BUDGET");
        assertThat(response.getBody().details()).containsEntry("retryAfterSeconds", 3600L);
    }

    @Test
    void openProviderCircuitIs503AndRetryable() {
        var response = handler.providerCircuitOpen(new ProviderCircuitOpenException(30));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PROVIDER_CIRCUIT_OPEN");
        assertThat(response.getBody().retryable()).isTrue();
    }
}
