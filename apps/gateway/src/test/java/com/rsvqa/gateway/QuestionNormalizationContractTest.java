package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the gateway's half of the question-normalization contract.
 *
 * <p>The model service now reports the canonical question it actually sent to the
 * frozen RSVQA-HR classifier. The gateway must carry that through without ever
 * letting the localized rendering stand in for the raw closed-set prediction, and
 * without attaching canonicalization metadata to external providers.
 */
class QuestionNormalizationContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESEARCH_PAYLOAD = """
            {
              "request_id": "req-1",
              "status": "answered",
              "supported": true,
              "prediction": "3",
              "answer": "3",
              "confidence": 0.72,
              "margin": 0.31,
              "top_k": [{"answer": "3", "probability": 0.72}],
              "original_question": "有几条路？",
              "canonical_question": "What is the amount of roads?",
              "canonical_question_display": "图中有多少条道路？",
              "model_input_question": "What is the amount of roads?",
              "question_normalizer_version": "2.0.0",
              "matched_intent": "count",
              "matched_objects": ["road"],
              "question_scope_verification": "release_anchored",
              "reason_code": "ok",
              "needs_clarification": false,
              "clarification_options": [],
              "interpretation_note": "已理解为：图中有多少条道路？",
              "display_answer": "3 条道路",
              "display_locale": "zh-CN",
              "answer_shape_mismatch": false,
              "question_type": "count",
              "predicted_question_type": "count",
              "question_type_probabilities": {"count": 1.0},
              "prediction_origin": "research_vilt_predicted_soft",
              "model_release_id": "rsvqa-hr-qdrop15-predicted-soft-20260724-8510bc9",
              "checkpoint_sha256": "aa",
              "answer_vocabulary_sha256": "bb",
              "runtime_artifact_sha256": "cc",
              "task_scope": "rsvqa_hr_grouped_closed_set",
              "limitations": ["Not open-ended VQA."],
              "capability_notice": "notice",
              "input_sha256": "dd",
              "latency_ms": 120,
              "runtime_mode": "real"
            }
            """;

    @Test
    void deserializesEveryNormalizationFieldFromTheModelService() throws Exception {
        ModelPredictionResponse response = MAPPER.readValue(RESEARCH_PAYLOAD, ModelPredictionResponse.class);

        assertThat(response.originalQuestion()).isEqualTo("有几条路？");
        assertThat(response.canonicalQuestion()).isEqualTo("What is the amount of roads?");
        assertThat(response.canonicalQuestionDisplay()).isEqualTo("图中有多少条道路？");
        assertThat(response.modelInputQuestion()).isEqualTo("What is the amount of roads?");
        assertThat(response.questionNormalizerVersion()).isEqualTo("2.0.0");
        assertThat(response.matchedIntent()).isEqualTo("count");
        assertThat(response.matchedObjects()).containsExactly("road");
        assertThat(response.questionScopeVerification()).isEqualTo("release_anchored");
        assertThat(response.reasonCode()).isEqualTo("ok");
        assertThat(response.needsClarification()).isFalse();
        assertThat(response.interpretationNote()).isEqualTo("已理解为：图中有多少条道路？");
        assertThat(response.displayAnswer()).isEqualTo("3 条道路");
        assertThat(response.displayLocale()).isEqualTo("zh-CN");
        assertThat(response.answerShapeMismatch()).isFalse();
    }

    @Test
    void apiResponseKeepsTheRawPredictionAndCarriesTheRenderingSeparately() throws Exception {
        ModelPredictionResponse model = MAPPER.readValue(RESEARCH_PAYLOAD, ModelPredictionResponse.class);

        ApiPredictionResponse api = ApiPredictionResponse.from(model);

        assertThat(api.answer()).isEqualTo("3");
        assertThat(api.presentation().displayAnswer()).isEqualTo("3 条道路");
        assertThat(api.presentation().displayLocale()).isEqualTo("zh-CN");
        assertThat(api.understanding().originalQuestion()).isEqualTo("有几条路？");
        assertThat(api.understanding().modelInputQuestion()).isEqualTo("What is the amount of roads?");
        assertThat(api.understanding().normalizerVersion()).isEqualTo("2.0.0");
        assertThat(api.understanding().matchedObjects()).containsExactly("road");
        assertThat(api.understanding().scopeVerification()).isEqualTo("release_anchored");
    }

    @Test
    void missingNormalizationFieldsDegradeToEmptyRatherThanNull() throws Exception {
        String legacy = """
                {"request_id":"r","status":"answered","supported":true,"prediction":"yes",
                 "capability_notice":"n","input_sha256":"x","latency_ms":1,"runtime_mode":"mock",
                 "prediction_origin":"mock_demo","task_scope":"rsvqa_hr_grouped_closed_set"}
                """;

        ApiPredictionResponse api = ApiPredictionResponse.from(
                MAPPER.readValue(legacy, ModelPredictionResponse.class));

        assertThat(api.answer()).isEqualTo("yes");
        assertThat(api.understanding().matchedObjects()).isEmpty();
        assertThat(api.understanding().clarificationOptions()).isEmpty();
        assertThat(api.understanding().needsClarification()).isFalse();
        assertThat(api.presentation().answerShapeMismatch()).isFalse();
    }

    @Test
    void clarificationIsReportedWithoutClaimingAnAnswer() throws Exception {
        String clarification = """
                {"request_id":"r","status":"unsupported","supported":false,
                 "original_question":"图中有多少住宅？",
                 "question_normalizer_version":"2.0.0",
                 "reason_code":"ambiguous_object_alias",
                 "needs_clarification":true,
                 "clarification_options":["住宅建筑","住宅区"],
                 "capability_notice":"n","input_sha256":"x","latency_ms":1,"runtime_mode":"real",
                 "prediction_origin":"not_applicable","task_scope":"rsvqa_hr_grouped_closed_set"}
                """;

        ApiPredictionResponse api = ApiPredictionResponse.from(
                MAPPER.readValue(clarification, ModelPredictionResponse.class));

        assertThat(api.status()).isEqualTo("unsupported");
        assertThat(api.answer()).isNull();
        assertThat(api.understanding().needsClarification()).isTrue();
        assertThat(api.understanding().clarificationOptions()).containsExactly("住宅建筑", "住宅区");
        assertThat(api.understanding().canonicalQuestion()).isNull();
    }

    @Test
    void externalProviderResultsCarryNoCanonicalization() {
        var understanding = ApiPredictionResponse.QuestionUnderstanding.notApplicable("图中有什么？");

        assertThat(understanding.originalQuestion()).isEqualTo("图中有什么？");
        assertThat(understanding.canonicalQuestion()).isNull();
        assertThat(understanding.modelInputQuestion()).isNull();
        assertThat(understanding.normalizerVersion()).isNull();
        assertThat(understanding.matchedObjects()).isEmpty();
        assertThat(understanding.scopeVerification()).isNull();
    }

    @Test
    void modelServiceShapeFlagWidensButNeverNarrowsTheLocalHeuristic() {
        ApiPredictionResponse serviceFlagged = research("3", "count", true);
        ApiPredictionResponse bothAgree = research("3", "count", false);
        ApiPredictionResponse legacyOnly = research("no", "count", false);

        assertThat(WorkspaceService.requiresManualReview(serviceFlagged)).isTrue();
        assertThat(WorkspaceService.requiresManualReview(bothAgree)).isFalse();
        assertThat(WorkspaceService.requiresManualReview(legacyOnly)).isTrue();
    }

    private static ApiPredictionResponse research(String answer, String questionType, boolean mismatch) {
        return new ApiPredictionResponse(
                "r", "answered", true, answer, 0.9, 0.5, List.of(),
                "What is the amount of roads?", questionType, questionType, java.util.Map.of(),
                "research_vilt_predicted_soft", "release", null, null, null,
                "rsvqa_hr_grouped_closed_set", List.of(), "notice",
                "model_answer_not_risk_guaranteed", false, true, true, 10L, "real",
                ApiPredictionResponse.QuestionUnderstanding.notApplicable("有几条路？"),
                new ApiPredictionResponse.AnswerPresentation(null, null, mismatch)
        );
    }
}
