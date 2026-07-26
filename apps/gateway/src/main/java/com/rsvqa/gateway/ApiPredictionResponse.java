package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiPredictionResponse(
        String requestId,
        String status,
        boolean supported,
        String answer,
        Double confidence,
        Double margin,
        List<ModelPredictionResponse.TopKPrediction> topK,
        String canonicalQuestion,
        String questionType,
        String predictedQuestionType,
        Map<String, Double> questionTypeProbabilities,
        String predictionOrigin,
        String modelReleaseId,
        String checkpointSha256,
        String answerVocabularySha256,
        String runtimeArtifactSha256,
        String taskScope,
        List<String> limitations,
        String capabilityNotice,
        Long latencyMs,
        String runtimeMode,
        QuestionUnderstanding understanding,
        AnswerPresentation presentation
) {

    /**
     * How the user's question was turned into the text the research model saw.
     *
     * <p>Grouped rather than flattened so that the external-provider path can
     * only ever supply {@link #notApplicable(String)} — there is no way to
     * accidentally populate half of it and imply a canonicalization that never
     * ran.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionUnderstanding(
            String originalQuestion,
            String canonicalQuestion,
            String canonicalQuestionDisplay,
            String modelInputQuestion,
            String normalizerVersion,
            String matchedIntent,
            List<String> matchedObjects,
            String scopeVerification,
            String reasonCode,
            boolean needsClarification,
            List<String> clarificationOptions,
            String interpretationNote
    ) {
        /** External providers receive the raw question and are never canonicalized. */
        static QuestionUnderstanding notApplicable(String originalQuestion) {
            return new QuestionUnderstanding(
                    originalQuestion, null, null, null, null, null,
                    List.of(), null, null, false, List.of(), null
            );
        }

        static QuestionUnderstanding from(ModelPredictionResponse response) {
            return new QuestionUnderstanding(
                    response.originalQuestion(),
                    response.canonicalQuestion(),
                    response.canonicalQuestionDisplay(),
                    response.modelInputQuestion(),
                    response.questionNormalizerVersion(),
                    response.matchedIntent(),
                    response.matchedObjects() == null ? List.of() : response.matchedObjects(),
                    response.questionScopeVerification(),
                    response.reasonCode(),
                    Boolean.TRUE.equals(response.needsClarification()),
                    response.clarificationOptions() == null ? List.of() : response.clarificationOptions(),
                    response.interpretationNote()
            );
        }
    }

    /**
     * Presentation-only rendering of the raw answer.
     *
     * <p>{@code displayAnswer} is never mapped into {@link #answer()}: the raw
     * closed-set prediction stays the persisted and audited value.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnswerPresentation(
            String displayAnswer,
            String displayLocale,
            boolean answerShapeMismatch
    ) {
        static final AnswerPresentation NONE = new AnswerPresentation(null, null, false);
    }

    static ApiPredictionResponse from(ModelPredictionResponse response) {
        return new ApiPredictionResponse(
                response.requestId(),
                response.status(),
                response.supported(),
                response.prediction() == null ? response.answer() : response.prediction(),
                response.confidence(),
                response.margin(),
                response.topK(),
                response.canonicalQuestion(),
                response.questionType(),
                response.predictedQuestionType(),
                response.questionTypeProbabilities(),
                response.predictionOrigin(),
                response.modelReleaseId(),
                response.checkpointSha256(),
                response.answerVocabularySha256(),
                response.runtimeArtifactSha256(),
                response.taskScope(),
                response.limitations(),
                response.capabilityNotice(),
                response.latencyMs(),
                response.runtimeMode(),
                QuestionUnderstanding.from(response),
                new AnswerPresentation(
                        response.displayAnswer(),
                        response.displayLocale(),
                        Boolean.TRUE.equals(response.answerShapeMismatch())
                )
        );
    }

    ApiPredictionResponse(
            String requestId,
            String status,
            boolean supported,
            String answer,
            String canonicalQuestion,
            String questionType,
            String predictionOrigin,
            String modelReleaseId,
            String capabilityNotice
    ) {
        this(
                requestId,
                status,
                supported,
                answer,
                null,
                null,
                List.of(),
                canonicalQuestion,
                questionType,
                questionType,
                Map.of(),
                predictionOrigin,
                modelReleaseId,
                null,
                null,
                null,
                "rsvqa_hr_grouped_closed_set",
                List.of(),
                capabilityNotice,
                null,
                "mock",
                QuestionUnderstanding.notApplicable(null),
                AnswerPresentation.NONE
        );
    }
}
