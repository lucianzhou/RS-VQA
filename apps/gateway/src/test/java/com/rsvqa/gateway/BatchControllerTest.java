package com.rsvqa.gateway;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BatchController.class)
@Import(SecurityConfiguration.class)
class BatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BatchService batches;

    @MockBean
    private BatchWorker worker;

    @Test
    void servesAnOwnedBatchItemImageInline() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        given(batches.imageContent(jobId, itemId))
                .willReturn(new BatchService.ImageContent(new byte[]{1, 2, 3}, "image/png", "遥感图.png"));

        mockMvc.perform(get("/api/v1/batch-jobs/{jobId}/items/{itemId}/image", jobId, itemId)
                        .with(user("demo")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("inline")))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
