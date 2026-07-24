package com.rsvqa.gateway;

import java.time.Duration;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class VqaService {

    private final WebClient modelServiceClient;
    private final ModelServiceProperties properties;
    private final Timer inferenceTimer;
    private final Counter inferenceErrors;

    public VqaService(
            @Qualifier("modelServiceClient") WebClient modelServiceClient,
            ModelServiceProperties properties,
            MeterRegistry registry
    ) {
        this.modelServiceClient = modelServiceClient;
        this.properties = properties;
        this.inferenceTimer = Timer.builder("rsvqa.model.inference")
                .description("End-to-end model-service invocation latency")
                .publishPercentileHistogram()
                .register(registry);
        this.inferenceErrors = Counter.builder("rsvqa.model.errors")
                .description("Model-service invocation failures")
                .register(registry);
    }

    public ApiPredictionResponse answer(MultipartFile image, String question) {
        try {
            byte[] bytes = image.getBytes();
            String filename = image.getOriginalFilename() == null ? "upload-image" : image.getOriginalFilename();
            String contentType = image.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : image.getContentType();
            return answer(bytes, filename, contentType, question, null);
        } catch (Exception error) {
            if (error instanceof ModelServiceException modelServiceException) {
                throw modelServiceException;
            }
            throw new ModelServiceException("无法调用模型服务：" + error.getMessage(), error);
        }
    }

    public ApiPredictionResponse answer(
            byte[] bytes,
            String filename,
            String contentType,
            String question,
            String modelReleaseId
    ) {
        Timer.Sample sample = Timer.start();
        try {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            ByteArrayResource imageResource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            body.part("image", imageResource).contentType(MediaType.parseMediaType(contentType));
            body.part("question", question);
            if (modelReleaseId != null && !modelReleaseId.isBlank()) {
                body.part("model_release_id", modelReleaseId);
            }
            ModelPredictionResponse response = modelServiceClient.post()
                    .uri("/v1/vqa")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(ModelPredictionResponse.class)
                    .block(Duration.ofSeconds(properties.timeoutSeconds()));
            if (response == null) {
                throw new ModelServiceException("模型服务未返回结果。");
            }
            return ApiPredictionResponse.from(response);
        } catch (Exception error) {
            inferenceErrors.increment();
            if (error instanceof ModelServiceException modelServiceException) {
                throw modelServiceException;
            }
            throw new ModelServiceException("无法调用模型服务：" + error.getMessage(), error);
        } finally {
            sample.stop(inferenceTimer);
        }
    }

    public RuntimeModelInfoResponse currentModel() {
        try {
            RuntimeModelInfoResponse response = modelServiceClient.get()
                    .uri("/models/current")
                    .retrieve()
                    .bodyToMono(RuntimeModelInfoResponse.class)
                    .block(Duration.ofSeconds(properties.timeoutSeconds()));
            if (response == null) {
                throw new ModelServiceException("模型服务未返回版本信息。");
            }
            return response;
        } catch (Exception error) {
            if (error instanceof ModelServiceException modelServiceException) {
                throw modelServiceException;
            }
            throw new ModelServiceException("无法查询模型版本：" + error.getMessage(), error);
        }
    }
}
