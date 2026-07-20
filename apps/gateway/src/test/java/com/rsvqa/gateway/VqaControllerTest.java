package com.rsvqa.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VqaController.class)
class VqaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VqaService vqaService;

    @MockBean
    private ModelServiceProperties properties;

    @Test
    void rejectsNonImageUpload() throws Exception {
        MockMultipartFile upload = new MockMultipartFile(
                "image",
                "notes.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "not an image".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/vqa/answers")
                        .file(upload)
                        .param("question", "图中有没有道路？"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void forwardsValidRequest() throws Exception {
        MockMultipartFile upload = new MockMultipartFile(
                "image",
                "demo.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[]{1, 2, 3}
        );
        given(properties.maxFileBytes()).willReturn(10_485_760L);
        given(vqaService.answer(any(), eq("图中有没有道路？")))
                .willReturn(new ApiPredictionResponse(
                        "request-1",
                        "answered",
                        true,
                        "yes",
                        "Is there a road?",
                        "presence",
                        "mock_demo",
                        "mvp-mock-demo-not-a-research-release",
                        "Mock output"
                ));

        mockMvc.perform(multipart("/api/v1/vqa/answers")
                        .file(upload)
                        .param("question", "图中有没有道路？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("yes"))
                .andExpect(jsonPath("$.predictionOrigin").value("mock_demo"));
    }
}
