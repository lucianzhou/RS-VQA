package com.rsvqa.gateway;

import java.util.List;

/**
 * Readable titles for RS-Bot sessions.
 *
 * <p>A session list is only useful if its rows say what the session was about.
 * Identifier-shaped titles ("Agent action 178491…") force users to open every
 * row to find anything.
 *
 * <p>Derivation is deterministic and local rather than model-generated: it costs
 * nothing, it cannot fail halfway through a run, and it produces the same title
 * for the same question. That makes it both the primary path and the stable
 * fallback demanded when nothing else is available.
 */
final class AgentSessionTitle {

    private static final int MAX_LENGTH = 40;
    private static final int MAX_CONTEXT_LENGTH = 16;

    /** Ordered so more specific topics win over generic ones. */
    private static final List<Topic> TOPICS = List.of(
            new Topic("置信度分布", "置信度", "confidence"),
            new Topic("失败项排查", "失败", "错误", "报错"),
            new Topic("超范围问题分析", "不支持", "超范围", "拒答", "澄清"),
            new Topic("报告草稿", "报告", "草稿", "report"),
            new Topic("批量任务分析", "批量", "批任务", "batch"),
            new Topic("审计追溯", "审计", "trace", "追溯"),
            new Topic("知识检索", "检索", "知识库", "文献", "论文"),
            new Topic("模型版本查询", "版本", "发布", "release", "checkpoint"),
            new Topic("系统状态检查", "健康", "状态", "health"),
            new Topic("能力边界确认", "支持哪些", "能力", "边界", "范围"),
            new Topic("结果汇总", "汇总", "统计", "分布", "概览", "摘要"),
            new Topic("单图问答", "图中", "图里", "这张图")
    );

    private record Topic(String label, String... keywords) {
        boolean matches(String text) {
            for (String keyword : keywords) {
                if (text.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }

    private AgentSessionTitle() {
    }

    /**
     * @param contextLabel  project name, conversation title or batch label
     * @param firstQuestion the question that opened the session
     * @return a title, never blank
     */
    static String derive(String contextLabel, String firstQuestion) {
        String question = firstQuestion == null ? "" : firstQuestion.trim();
        String topic = topicOf(question);
        String context = shorten(contextLabel, MAX_CONTEXT_LENGTH);

        if (topic == null) {
            // No recognised topic: the question itself is the most informative
            // thing available, so use it rather than inventing a label.
            String fromQuestion = shorten(question, MAX_LENGTH);
            if (!fromQuestion.isBlank()) {
                return fromQuestion;
            }
            return context.isBlank() ? "RS-Bot 分析" : context + " · 分析";
        }
        return context.isBlank() ? topic : context + " · " + topic;
    }

    private static String topicOf(String question) {
        String text = question.toLowerCase(java.util.Locale.ROOT);
        for (Topic topic : TOPICS) {
            if (topic.matches(text)) {
                return topic.label();
            }
        }
        return null;
    }

    /**
     * True when a title is still the placeholder created with the session, and
     * therefore safe to replace with something derived from the first question.
     */
    static boolean isPlaceholder(String title) {
        if (title == null || title.isBlank()) {
            return true;
        }
        String trimmed = title.trim();
        return trimmed.equals("工作区可信分析")
                || trimmed.endsWith(" · 项目分析")
                || trimmed.endsWith(" · 会话分析")
                || trimmed.endsWith(" · 结果分析")
                || trimmed.startsWith("Agent action")
                || trimmed.startsWith("新分析");
    }

    private static String shorten(String value, int limit) {
        if (value == null) {
            return "";
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        collapsed = collapsed.replaceAll("[?？。.!！]+$", "");
        return collapsed.length() <= limit ? collapsed : collapsed.substring(0, limit) + "…";
    }
}
