package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FileStorageServiceTest {

    @Test
    void readsVp8xDimensionsWithoutTrustingFilename() {
        byte[] webp = new byte[30];
        put(webp, 0, "RIFF");
        webp[4] = 22;
        put(webp, 8, "WEBP");
        put(webp, 12, "VP8X");
        webp[16] = 10;
        webp[24] = 0x3f;
        webp[27] = 0x1f;

        var dimensions = FileStorageService.webpDimensions(webp);

        assertThat(dimensions.width()).isEqualTo(64);
        assertThat(dimensions.height()).isEqualTo(32);
    }

    @Test
    void rejectsTruncatedWebp() {
        assertThatThrownBy(() -> FileStorageService.webpDimensions("RIFF".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(RequestValidationException.class);
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] source = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(source, 0, target, offset, source.length);
    }
}
