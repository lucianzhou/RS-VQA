package com.rsvqa.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DemoEnvironmentStore {

    private final NamedParameterJdbcTemplate jdbc;

    DemoEnvironmentStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> storageKeys(UUID userId) {
        Map<String, UUID> parameters = Map.of("userId", userId);
        List<String> keys = new ArrayList<>();
        keys.addAll(jdbc.query(
                """
                SELECT image.storage_key
                FROM image_asset image
                JOIN conversation conversation ON conversation.id = image.conversation_id
                JOIN project project ON project.id = conversation.project_id
                WHERE project.user_id = :userId
                """,
                parameters,
                (result, row) -> result.getString(1)
        ));
        keys.addAll(jdbc.query(
                """
                SELECT item.storage_key
                FROM batch_item item
                JOIN batch_job job ON job.id = item.batch_job_id
                WHERE job.user_id = :userId
                  AND item.storage_key IS NOT NULL
                """,
                parameters,
                (result, row) -> result.getString(1)
        ));
        return keys.stream().filter(key -> key != null && !key.isBlank()).distinct().toList();
    }

    ClearCounts clearUserData(UUID userId) {
        Map<String, UUID> parameters = Map.of("userId", userId);
        int proposals = update("""
                DELETE FROM agent_action_proposal WHERE user_id = :userId
                """, parameters);
        int tools = update("""
                DELETE FROM tool_invocation
                WHERE agent_run_id IN (SELECT id FROM agent_run WHERE user_id = :userId)
                """, parameters);
        int runs = update("DELETE FROM agent_run WHERE user_id = :userId", parameters);
        int sessions = update("DELETE FROM agent_session WHERE user_id = :userId", parameters);
        int reportVersions = update("""
                DELETE FROM report_version
                WHERE report_id IN (SELECT id FROM report WHERE user_id = :userId)
                """, parameters);
        int reports = update("DELETE FROM report WHERE user_id = :userId", parameters);
        int batchItems = update("""
                DELETE FROM batch_item
                WHERE batch_job_id IN (SELECT id FROM batch_job WHERE user_id = :userId)
                """, parameters);
        int batchJobs = update("DELETE FROM batch_job WHERE user_id = :userId", parameters);
        int messages = update("""
                DELETE FROM message
                WHERE conversation_id IN (
                    SELECT conversation.id
                    FROM conversation
                    JOIN project ON project.id = conversation.project_id
                    WHERE project.user_id = :userId
                )
                """, parameters);
        int invocations = update("""
                DELETE FROM model_invocation
                WHERE conversation_id IN (
                    SELECT conversation.id
                    FROM conversation
                    JOIN project ON project.id = conversation.project_id
                    WHERE project.user_id = :userId
                )
                """, parameters);
        int images = update("""
                DELETE FROM image_asset
                WHERE conversation_id IN (
                    SELECT conversation.id
                    FROM conversation
                    JOIN project ON project.id = conversation.project_id
                    WHERE project.user_id = :userId
                )
                """, parameters);
        int conversations = update("""
                DELETE FROM conversation
                WHERE project_id IN (SELECT id FROM project WHERE user_id = :userId)
                """, parameters);
        int projects = update("DELETE FROM project WHERE user_id = :userId", parameters);
        int audits = update("DELETE FROM audit_event WHERE user_id = :userId", parameters);
        return new ClearCounts(
                proposals, tools, runs, sessions, reportVersions, reports, batchItems, batchJobs,
                messages, invocations, images, conversations, projects, audits
        );
    }

    private int update(String sql, Map<String, UUID> parameters) {
        return jdbc.update(sql, parameters);
    }

    record ClearCounts(
            int proposals,
            int tools,
            int runs,
            int sessions,
            int reportVersions,
            int reports,
            int batchItems,
            int batchJobs,
            int messages,
            int invocations,
            int images,
            int conversations,
            int projects,
            int audits
    ) {
        int totalRows() {
            return proposals + tools + runs + sessions + reportVersions + reports + batchItems
                    + batchJobs + messages + invocations + images + conversations + projects + audits;
        }
    }
}
