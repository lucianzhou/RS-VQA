package com.rsvqa.gateway;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", ".png",
            "image/jpeg", ".jpg",
            "image/webp", ".webp"
    );

    private final Path root;
    private final ModelServiceProperties modelProperties;

    public FileStorageService(StorageProperties properties, ModelServiceProperties modelProperties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.modelProperties = modelProperties;
        cleanupStaleTemporaryFiles(Duration.ofHours(24));
    }

    public StoredImage store(UUID userId, UUID conversationId, MultipartFile upload) {
        return storeInNamespace(userId + "/" + conversationId, upload);
    }

    public StoredImage storeBatch(UUID userId, UUID batchId, MultipartFile upload) {
        return storeInNamespace(userId + "/batch/" + batchId, upload);
    }

    private StoredImage storeInNamespace(String namespace, MultipartFile upload) {
        String contentType = upload.getContentType();
        if (upload.isEmpty()) {
            throw new RequestValidationException("图像文件不能为空。");
        }
        if (upload.getSize() > modelProperties.maxFileBytes()) {
            throw new RequestValidationException("图像文件不能超过 10 MiB。");
        }
        if (!EXTENSIONS.containsKey(contentType)) {
            throw new RequestValidationException("仅接受 PNG、JPG 或 WEBP 图像。");
        }
        try {
            byte[] bytes = upload.getBytes();
            ImageDimensions dimensions = dimensions(bytes, contentType);
            String storageKey = namespace + "/" + UUID.randomUUID() + EXTENSIONS.get(contentType);
            Path target = resolve(storageKey);
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            String original = upload.getOriginalFilename() == null ? "image" : safeDisplayName(upload.getOriginalFilename());
            return new StoredImage(
                    storageKey,
                    original,
                    sha256(bytes),
                    contentType,
                    bytes.length,
                    dimensions.width(),
                    dimensions.height()
            );
        } catch (IOException error) {
            throw new RequestValidationException("图像文件无法安全保存。");
        }
    }

    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException error) {
            throw new ResourceNotFoundException("图像文件不存在或不可读取。");
        }
    }

    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ignored) {
            // Database state remains authoritative; lifecycle cleanup can retry this file.
        }
    }

    void cleanupStaleTemporaryFiles(Duration maximumAge) {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minus(maximumAge);
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(".upload-"))
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                        } catch (IOException ignored) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // A later application start can retry stale temporary files.
                        }
                    });
        } catch (IOException ignored) {
            // Storage initialization remains available even when cleanup cannot scan.
        }
    }

    private Path resolve(String storageKey) {
        Path candidate = root.resolve(storageKey).normalize();
        if (!candidate.startsWith(root)) {
            throw new RequestValidationException("文件存储路径无效。");
        }
        return candidate;
    }

    private static String safeDisplayName(String original) {
        String leaf = Path.of(original).getFileName().toString();
        String cleaned = leaf.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isBlank()) {
            return "image";
        }
        return cleaned.substring(0, Math.min(cleaned.length(), 255));
    }

    private static ImageDimensions dimensions(byte[] bytes, String contentType) throws IOException {
        if ("image/webp".equals(contentType)) {
            return webpDimensions(bytes);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new RequestValidationException("上传文件不是可读取的图像。");
        }
        return new ImageDimensions(image.getWidth(), image.getHeight());
    }

    static ImageDimensions webpDimensions(byte[] bytes) {
        if (bytes.length < 30 || !ascii(bytes, 0, "RIFF") || !ascii(bytes, 8, "WEBP")) {
            throw new RequestValidationException("上传文件不是有效的 WEBP 图像。");
        }
        long declaredSize = unsignedLittleEndian32(bytes, 4) + 8;
        if (declaredSize > bytes.length) {
            throw new RequestValidationException("WEBP 图像数据不完整。");
        }
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String chunk = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long chunkSize = unsignedLittleEndian32(bytes, offset + 4);
            int data = offset + 8;
            if (chunkSize > Integer.MAX_VALUE || data + chunkSize > bytes.length) {
                throw new RequestValidationException("WEBP 图像块不完整。");
            }
            if ("VP8X".equals(chunk) && chunkSize >= 10) {
                return checkedDimensions(
                        1 + unsignedLittleEndian24(bytes, data + 4),
                        1 + unsignedLittleEndian24(bytes, data + 7)
                );
            }
            if ("VP8L".equals(chunk) && chunkSize >= 5 && (bytes[data] & 0xff) == 0x2f) {
                int packed = (int) unsignedLittleEndian32(bytes, data + 1);
                return checkedDimensions(1 + (packed & 0x3fff), 1 + ((packed >>> 14) & 0x3fff));
            }
            if ("VP8 ".equals(chunk) && chunkSize >= 10
                    && (bytes[data + 3] & 0xff) == 0x9d
                    && (bytes[data + 4] & 0xff) == 0x01
                    && (bytes[data + 5] & 0xff) == 0x2a) {
                int width = ((bytes[data + 7] & 0xff) << 8 | (bytes[data + 6] & 0xff)) & 0x3fff;
                int height = ((bytes[data + 9] & 0xff) << 8 | (bytes[data + 8] & 0xff)) & 0x3fff;
                return checkedDimensions(width, height);
            }
            offset = data + (int) chunkSize + ((int) chunkSize & 1);
        }
        throw new RequestValidationException("无法读取 WEBP 图像尺寸。");
    }

    private static ImageDimensions checkedDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new RequestValidationException("图像尺寸无效。");
        }
        return new ImageDimensions(width, height);
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset + value.length() > bytes.length) return false;
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != (byte) value.charAt(index)) return false;
        }
        return true;
    }

    private static long unsignedLittleEndian32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static int unsignedLittleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    public record StoredImage(
            String storageKey,
            String originalName,
            String sha256,
            String mimeType,
            long sizeBytes,
            int width,
            int height
    ) {
    }

    record ImageDimensions(int width, int height) {
    }
}
