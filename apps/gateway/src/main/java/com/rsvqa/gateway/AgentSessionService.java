package com.rsvqa.gateway;

import static com.rsvqa.gateway.AgentDtos.*;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rsvqa.gateway.domain.AgentRunEntity;
import com.rsvqa.gateway.domain.AgentSessionEntity;
import com.rsvqa.gateway.domain.BatchJobEntity;
import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.ToolInvocationEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentRunRepository;
import com.rsvqa.gateway.repository.AgentSessionRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.ToolInvocationRepository;
import com.rsvqa.gateway.repository.UserRepository;

@Service
public class AgentSessionService {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ConversationRepository conversations;
    private final BatchJobRepository batches;
    private final AgentSessionRepository sessions;
    private final AgentRunRepository runs;
    private final ToolInvocationRepository toolInvocations;

    public AgentSessionService(
            UserRepository users,
            ProjectRepository projects,
            ConversationRepository conversations,
            BatchJobRepository batches,
            AgentSessionRepository sessions,
            AgentRunRepository runs,
            ToolInvocationRepository toolInvocations
    ) {
        this.users = users;
        this.projects = projects;
        this.conversations = conversations;
        this.batches = batches;
        this.sessions = sessions;
        this.runs = runs;
        this.toolInvocations = toolInvocations;
    }

    @Transactional
    public AgentSessionDetail create(CreateAgentSessionRequest request) {
        UserEntity user = currentUser();
        int contextCount = (request.projectId() == null ? 0 : 1)
                + (request.conversationId() == null ? 0 : 1)
                + (request.batchJobId() == null ? 0 : 1);
        if (contextCount > 1) {
            throw new RequestValidationException("Agent 会话只能绑定一个项目、对话或批量任务上下文。");
        }
        ProjectEntity project = request.projectId() == null ? null
                : projects.findByIdAndUserId(request.projectId(), user.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("项目不存在。"));
        ConversationEntity conversation = request.conversationId() == null ? null
                : conversations.findByIdAndProjectUserId(request.conversationId(), user.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("会话不存在。"));
        BatchJobEntity batch = request.batchJobId() == null ? null
                : batches.findByIdAndUserId(request.batchJobId(), user.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("批量任务不存在。"));
        String title = request.title() == null || request.title().isBlank()
                ? defaultTitle(project, conversation, batch)
                : request.title().trim();
        AgentSessionEntity session = sessions.save(new AgentSessionEntity(
                user, project, conversation, batch, title
        ));
        return detail(session);
    }

    @Transactional(readOnly = true)
    public List<AgentSessionSummary> list() {
        UUID userId = currentUser().getId();
        return sessions.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(userId)
                .stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public AgentSessionDetail get(UUID id) {
        return detail(owned(id));
    }

    @Transactional
    public void archive(UUID id) {
        owned(id).archive();
    }

    @Transactional(readOnly = true)
    public AgentSessionEntity owned(UUID id) {
        return sessions.findByIdAndUserId(id, currentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent 会话不存在。"));
    }

    private AgentSessionSummary summary(AgentSessionEntity session) {
        return new AgentSessionSummary(
                session.getId(),
                session.getTitle(),
                contextType(session),
                contextId(session),
                contextLabel(session),
                Math.toIntExact(runs.countByAgentSessionId(session.getId())),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private AgentSessionDetail detail(AgentSessionEntity session) {
        List<AgentHistoryRun> history = runs.findByAgentSessionIdOrderByCreatedAtAsc(session.getId())
                .stream().map(this::historyRun).toList();
        return new AgentSessionDetail(
                session.getId(),
                session.getTitle(),
                contextType(session),
                contextId(session),
                contextLabel(session),
                history,
                suggestions(session),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private AgentHistoryRun historyRun(AgentRunEntity run) {
        List<ToolCallResponse> calls = toolInvocations.findByAgentRunIdOrderByCreatedAtAsc(run.getId())
                .stream().map(this::toolCall).toList();
        return new AgentHistoryRun(
                run.getId(),
                run.getStatus(),
                run.getInputText(),
                run.getOutputText(),
                run.getTraceId(),
                run.getLatencyMs(),
                run.getProviderId(),
                run.getProviderModel(),
                run.getTotalTokens(),
                calls,
                run.getCreatedAt()
        );
    }

    private ToolCallResponse toolCall(ToolInvocationEntity call) {
        return new ToolCallResponse(
                call.getId(),
                call.getToolName(),
                call.getStatus(),
                call.getArgumentsSummary(),
                call.getOutputSummary(),
                call.getLatencyMs() == null ? 0 : call.getLatencyMs()
        );
    }

    private static String contextType(AgentSessionEntity session) {
        if (session.getProject() != null) return "PROJECT";
        if (session.getConversation() != null) return "CONVERSATION";
        if (session.getBatchJob() != null) return "BATCH_JOB";
        return "WORKSPACE";
    }

    private static UUID contextId(AgentSessionEntity session) {
        if (session.getProject() != null) return session.getProject().getId();
        if (session.getConversation() != null) return session.getConversation().getId();
        if (session.getBatchJob() != null) return session.getBatchJob().getId();
        return null;
    }

    private static String contextLabel(AgentSessionEntity session) {
        if (session.getProject() != null) return session.getProject().getName();
        if (session.getConversation() != null) return session.getConversation().getTitle();
        if (session.getBatchJob() != null) {
            return "批量任务 " + session.getBatchJob().getId().toString().substring(0, 8);
        }
        return "整个工作区";
    }

    private static String defaultTitle(
            ProjectEntity project,
            ConversationEntity conversation,
            BatchJobEntity batch
    ) {
        if (project != null) return project.getName() + " · 项目分析";
        if (conversation != null) return conversation.getTitle() + " · 会话分析";
        if (batch != null) return "批量任务 " + batch.getId().toString().substring(0, 8) + " · 结果分析";
        return "工作区可信分析";
    }

    private static List<String> suggestions(AgentSessionEntity session) {
        if (session.getProject() != null) {
            return List.of(
                    "汇总这个项目的 VQA 结果和置信度分布",
                    "列出需要人工复核的低置信度案例",
                    "生成这个项目的报告事实包",
                    "检索论文模型的能力边界"
            );
        }
        if (session.getBatchJob() != null) {
            return List.of(
                    "汇总这个批量任务的答案与问题类型分布",
                    "检查失败项和超范围问题",
                    "列出需要人工复核的案例",
                    "生成这个批量任务的报告事实包"
            );
        }
        if (session.getConversation() != null) {
            return List.of(
                    "读取当前会话历史",
                    "这个模型支持哪些问题？",
                    "查询当前模型版本",
                    "检查系统健康状态"
            );
        }
        return List.of(
                "查询当前模型版本",
                "这个模型支持哪些问题？",
                "检查系统健康状态",
                "检索论文模型的核准指标"
        );
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }
}
