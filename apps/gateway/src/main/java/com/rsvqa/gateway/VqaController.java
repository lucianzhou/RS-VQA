package com.rsvqa.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class VqaController {

    private final VqaService vqaService;
    private final ModelServiceProperties properties;

    public VqaController(VqaService vqaService, ModelServiceProperties properties) {
        this.vqaService = vqaService;
        this.properties = properties;
    }

    @PostMapping(value = "/api/v1/vqa/answers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiPredictionResponse> answer(
            @RequestPart("image") MultipartFile image,
            @RequestParam("question") String question
    ) {
        validate(image, question);
        return ResponseEntity.ok(vqaService.answer(image, question.trim()));
    }

    private void validate(MultipartFile image, String question) {
        if (image.isEmpty()) {
            throw new RequestValidationException("图像文件不能为空。");
        }
        if (image.getSize() > properties.maxFileBytes()) {
            throw new RequestValidationException("图像文件不能超过 10 MiB。");
        }
        if (image.getContentType() == null || !image.getContentType().startsWith("image/")) {
            throw new RequestValidationException("仅接受图片文件。");
        }
        if (question == null || question.trim().isEmpty()) {
            throw new RequestValidationException("请输入问题。");
        }
        if (question.length() > 300) {
            throw new RequestValidationException("问题不能超过 300 个字符。");
        }
    }
}
