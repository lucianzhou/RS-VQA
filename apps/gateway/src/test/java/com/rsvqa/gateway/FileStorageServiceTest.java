package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStorageServiceTest {

    @TempDir
    Path temporary;

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

    @Test
    void ownedDeletionCannotCrossTheUserNamespace() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Path otherFile = temporary.resolve(other + "/image.png");
        Files.createDirectories(otherFile.getParent());
        Files.write(otherFile, new byte[] {1, 2, 3});
        FileStorageService storage = new FileStorageService(
                new StorageProperties(temporary.toString()),
                new ModelServiceProperties("http://localhost", 2, 10 * 1024 * 1024)
        );

        assertThatThrownBy(() -> storage.deleteOwned(owner, other + "/image.png"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("不属于演示用户");
        assertThat(Files.exists(otherFile)).isTrue();

        Path ownedFile = temporary.resolve(owner + "/image.png");
        Files.createDirectories(ownedFile.getParent());
        Files.write(ownedFile, new byte[] {1, 2, 3});
        storage.deleteOwned(owner, owner + "/image.png");
        assertThat(Files.exists(ownedFile)).isFalse();
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] source = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(source, 0, target, offset, source.length);
    }
}
