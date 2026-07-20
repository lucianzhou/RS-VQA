package com.rsvqa.gateway;

import java.time.Duration;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class VqaService {

    private final WebClient modelServiceClient;
    private final ModelServiceProperties properties;

    public VqaService(WebClient modelServiceClient, ModelServiceProperties properties) {
        this.modelServiceClient = modelServiceClient;
        this.properties = properties;
    }

    public ApiPredictionResponse answer(MultipartFile image, String question) {
        try {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            byte[] bytes = image.getBytes();
            String filename = image.getOriginalFilename() == null ? "upload-image" : image.getOriginalFilename();
            String contentType = image.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : image.getContentType();
            ByteArrayResource imageResource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            body.part("image", imageResource).contentType(MediaType.parseMediaType(contentType));
            body.part("question", question);

            ModelPredictionResponse response = modelServiceClient.post()
                    .uri("/v1/predict")
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
            if (error instanceof ModelServiceException modelServiceException) {
                throw modelServiceException;
            }
            throw new ModelServiceException("无法调用模型服务：" + error.getMessage(), error);
        }
    }
}
