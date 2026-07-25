package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkspaceServiceTest {

    @Test
    void flagsAnswerShapesThatConflictWithThePredictedQuestionType() {
        assertThat(WorkspaceService.hasAnswerShapeMismatch("count", "no")).isTrue();
        assertThat(WorkspaceService.hasAnswerShapeMismatch("count", "3")).isFalse();
        assertThat(WorkspaceService.hasAnswerShapeMismatch("presence", "12")).isTrue();
        assertThat(WorkspaceService.hasAnswerShapeMismatch("presence", "yes")).isFalse();
        assertThat(WorkspaceService.hasAnswerShapeMismatch("area", "between 10m2 and 100m2")).isFalse();
    }
}
