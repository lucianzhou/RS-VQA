package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentSessionTitleTest {

    @Test
    void derivesATopicFromTheOpeningQuestion() {
        assertThat(AgentSessionTitle.derive("城市土地利用", "列出需要人工复核的低置信度案例"))
                .isEqualTo("城市土地利用 · 低置信度分析");
        assertThat(AgentSessionTitle.derive("城市土地利用", "汇总这个批量任务的答案分布"))
                .isEqualTo("城市土地利用 · 批量任务分析");
        assertThat(AgentSessionTitle.derive("城市土地利用", "生成这个项目的报告草稿"))
                .isEqualTo("城市土地利用 · 报告草稿");
        assertThat(AgentSessionTitle.derive("", "查询当前模型版本"))
                .isEqualTo("模型版本查询");
    }

    @Test
    void fallsBackToTheQuestionWhenNoTopicMatches() {
        assertThat(AgentSessionTitle.derive("项目一", "这片区域的绿化情况如何"))
                .isEqualTo("这片区域的绿化情况如何");
    }

    @Test
    void neverReturnsBlank() {
        assertThat(AgentSessionTitle.derive(null, null)).isEqualTo("RS-Bot 分析");
        assertThat(AgentSessionTitle.derive("", "   ")).isEqualTo("RS-Bot 分析");
        assertThat(AgentSessionTitle.derive("项目一", "")).isEqualTo("项目一 · 分析");
    }

    @Test
    void truncatesLongContextAndQuestion() {
        String title = AgentSessionTitle.derive("非常非常非常非常非常非常非常长的项目名称", "低置信度");

        assertThat(title).contains("…");
        assertThat(title.length()).isLessThan(45);
    }

    @Test
    void recognisesPlaceholderTitlesButNotUserChosenOnes() {
        assertThat(AgentSessionTitle.isPlaceholder("工作区可信分析")).isTrue();
        assertThat(AgentSessionTitle.isPlaceholder("城市土地利用 · 项目分析")).isTrue();
        assertThat(AgentSessionTitle.isPlaceholder("Agent action 178491ab")).isTrue();
        assertThat(AgentSessionTitle.isPlaceholder(null)).isTrue();
        assertThat(AgentSessionTitle.isPlaceholder("  ")).isTrue();

        assertThat(AgentSessionTitle.isPlaceholder("我自己命名的会话")).isFalse();
        assertThat(AgentSessionTitle.isPlaceholder("城市土地利用 · 低置信度分析")).isFalse();
    }

    @Test
    void stripsTrailingPunctuationSoTitlesReadCleanly() {
        assertThat(AgentSessionTitle.derive("项目一", "这片区域的绿化情况如何？"))
                .doesNotEndWith("？");
    }
}
