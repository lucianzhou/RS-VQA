package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.ReportEntity;
import com.rsvqa.gateway.domain.UserEntity;

class ReportEntityTest {

    @Test
    void versionsReturnToDraftAndConfirmationIsExplicit() {
        UserEntity user = new UserEntity("demo", null, "演示用户", "USER", true);
        ProjectEntity project = new ProjectEntity(user, "城市土地利用");
        ReportEntity report = new ReportEntity(user, project, null, "项目分析报告", "PROJECT_ANALYSIS", "trace-1");

        assertThat(report.getStatus()).isEqualTo("DRAFT");
        assertThat(report.getCurrentVersion()).isEqualTo(1);

        report.confirm();
        assertThat(report.getStatus()).isEqualTo("CONFIRMED");
        assertThat(report.getConfirmedAt()).isNotNull();

        report.advanceVersion();
        assertThat(report.getStatus()).isEqualTo("DRAFT");
        assertThat(report.getCurrentVersion()).isEqualTo(2);
        assertThat(report.getConfirmedAt()).isNull();
    }
}
