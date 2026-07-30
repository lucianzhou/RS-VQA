package com.rsvqa.gateway;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
class DemoShowcaseCatalog {

    static final String QUESTIONS_FILE = "showcase-24-questions.csv";
    static final String EXPECTED_QUESTIONS_SHA256 =
            "4dc3608b2c5853b2c8f63822331af50d348037c005365a1d17743d783b6bc283";
    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "run_index", "sample_id", "image_file", "question_type", "question", "primary_object"
    );
    private static final Set<String> QUESTION_TYPES = Set.of("presence", "count", "area", "comp");

    List<ShowcaseCase> load(Path configuredRoot) {
        if (configuredRoot == null) {
            throw new RequestValidationException("答辩演示数据目录未配置。");
        }
        try {
            Path root = configuredRoot.toAbsolutePath().normalize().toRealPath();
            Path questions = root.resolve(QUESTIONS_FILE).normalize();
            if (!questions.startsWith(root) || !Files.isRegularFile(questions)) {
                throw new RequestValidationException("答辩演示题目清单不存在。");
            }
            Path realQuestions = questions.toRealPath();
            if (!realQuestions.startsWith(root)) {
                throw new RequestValidationException("答辩演示题目清单越过冻结目录。");
            }
            if (!EXPECTED_QUESTIONS_SHA256.equals(sha256(realQuestions))) {
                throw new RequestValidationException("答辩演示题目清单校验失败，未执行重置。");
            }

            List<ShowcaseCase> cases = parse(root, realQuestions);
            validate(cases);
            return List.copyOf(cases);
        } catch (IOException error) {
            throw new RequestValidationException("答辩演示数据不可读取，未执行重置。");
        }
    }

    private static List<ShowcaseCase> parse(Path root, Path questions) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .get();
        try (Reader reader = Files.newBufferedReader(questions, StandardCharsets.UTF_8);
                var parser = format.parse(reader)) {
            if (!parser.getHeaderMap().keySet().containsAll(REQUIRED_HEADERS)) {
                throw new RequestValidationException("答辩演示题目清单字段不完整。");
            }
            List<ShowcaseCase> result = new ArrayList<>();
            for (CSVRecord record : parser) {
                String imageFile = required(record, "image_file");
                Path relative = Path.of(imageFile).normalize();
                if (relative.isAbsolute() || relative.startsWith("..") || !imageFile.startsWith("images/")) {
                    throw new RequestValidationException("答辩演示图像路径超出冻结目录。");
                }
                Path image = root.resolve(relative).normalize();
                if (!image.startsWith(root)) {
                    throw new RequestValidationException("答辩演示图像路径超出冻结目录。");
                }
                Path realImage = image.toRealPath();
                if (!realImage.startsWith(root) || !Files.isRegularFile(realImage)) {
                    throw new RequestValidationException("答辩演示图像不存在或越过冻结目录。");
                }
                String question = required(record, "question");
                if (question.length() > 300) {
                    throw new RequestValidationException("答辩演示问题超过系统长度限制。");
                }
                result.add(new ShowcaseCase(
                        required(record, "sample_id"),
                        relative.toString().replace('\\', '/'),
                        realImage,
                        required(record, "question_type").toLowerCase(),
                        question,
                        required(record, "primary_object")
                ));
            }
            return result;
        }
    }

    private static void validate(List<ShowcaseCase> cases) {
        if (cases.size() != 24) {
            throw new RequestValidationException("答辩演示清单必须恰好包含 24 个样本。");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> sampleIds = new LinkedHashSet<>();
        Set<Path> images = new LinkedHashSet<>();
        for (ShowcaseCase item : cases) {
            if (!QUESTION_TYPES.contains(item.questionType())) {
                throw new RequestValidationException("答辩演示清单包含未知问题类型。");
            }
            counts.merge(item.questionType(), 1, Integer::sum);
            if (!sampleIds.add(item.sampleId()) || !images.add(item.imagePath())) {
                throw new RequestValidationException("答辩演示清单中的样本或图像不唯一。");
            }
        }
        for (String type : QUESTION_TYPES) {
            if (counts.getOrDefault(type, 0) != 6) {
                throw new RequestValidationException("答辩演示清单必须为四类问题各 6 条。");
            }
        }
    }

    private static String required(CSVRecord record, String name) {
        String value = record.get(name);
        if (value == null || value.isBlank()) {
            throw new RequestValidationException("答辩演示题目清单存在空字段：" + name);
        }
        return value.trim();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 不支持 SHA-256。", error);
        }
    }

    record ShowcaseCase(
            String sampleId,
            String imageFile,
            Path imagePath,
            String questionType,
            String question,
            String primaryObject
    ) {
        String originalName() {
            return imagePath.getFileName().toString();
        }
    }
}
