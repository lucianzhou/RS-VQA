package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoShowcaseCatalogTest {

    @TempDir
    Path temporary;

    private final DemoShowcaseCatalog catalog = new DemoShowcaseCatalog();

    @Test
    void loadsTheFrozenQuestionsOnlyShowcaseWithoutEvaluationAnswers() throws Exception {
        Path root = Path.of("../../data/defense-benchmark-v1").toRealPath();

        var cases = catalog.load(root);

        assertThat(cases).hasSize(24);
        assertThat(cases).extracting(DemoShowcaseCatalog.ShowcaseCase::questionType)
                .containsOnly("presence", "count", "area", "comp");
        assertThat(cases.stream().map(DemoShowcaseCatalog.ShowcaseCase::imagePath).distinct())
                .hasSize(24);
    }

    @Test
    void rejectsAQuestionsFileWhoseFrozenDigestDoesNotMatch() throws Exception {
        Files.writeString(
                temporary.resolve(DemoShowcaseCatalog.QUESTIONS_FILE),
                "run_index,sample_id,image_file,question_type,question,primary_object\n"
        );

        assertThatThrownBy(() -> catalog.load(temporary))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("校验失败");
    }
}
