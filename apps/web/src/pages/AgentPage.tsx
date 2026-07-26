import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  Archive,
  ChevronDown,
  Clock3,
  Database,
  FileSearch,
  FileText,
  LoaderCircle,
  Plus,
  ShieldCheck,
  Sparkles,
  Check,
  X,
  Workflow,
} from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import {
  archiveAgentSession,
  createAgentSession,
  confirmAgentAction,
  listAgentSessions,
  listAgentActions,
  listBatchJobs,
  listReports,
  listProjects,
  proposeAgentAction,
  rejectAgentAction,
} from "../api";
import { AppTopbar, StatusBadge } from "../components/AppChrome";
import { RsBotChat } from "../components/RsBotChat";
import { RS_BOT_NAME, RS_BOT_SUBTITLE, useRsBotSession } from "../rsbot";
import type { AgentActionName, AgentActionProposal, AgentSession, ReportSummary } from "../types";

type ContextType = "WORKSPACE" | "PROJECT" | "BATCH_JOB";

export function AgentPage() {
  const queryClient = useQueryClient();
  const [activeSessionId, setActiveSessionId] = useState<string>();
  const [contextType, setContextType] = useState<ContextType>("PROJECT");
  const [actionPanelOpen, setActionPanelOpen] = useState(false);
  const [contextId, setContextId] = useState("");
  const sessions = useQuery({ queryKey: ["agent-sessions"], queryFn: listAgentSessions });
  const projects = useQuery({ queryKey: ["projects"], queryFn: listProjects });
  const batches = useQuery({ queryKey: ["batch-jobs"], queryFn: listBatchJobs });
  const reports = useQuery({ queryKey: ["reports"], queryFn: listReports });
  const actions = useQuery({
    queryKey: ["agent-actions", activeSessionId],
    queryFn: () => listAgentActions(activeSessionId),
    enabled: Boolean(activeSessionId),
  });
  const pendingActions = (actions.data ?? []).filter((item) => item.status === "PENDING");
  const showActionPanel = actionPanelOpen || pendingActions.length > 0;

  useEffect(() => {
    if (!activeSessionId && sessions.data?.[0]) setActiveSessionId(sessions.data[0].id);
  }, [activeSessionId, sessions.data]);
  // Same hook the workspace drawer uses, so both surfaces drive one session.
  const rsBot = useRsBotSession({ sessionId: activeSessionId });

  const contextOptions = useMemo(() => {
    if (contextType === "PROJECT") {
      return (projects.data ?? []).map((project) => ({ id: project.id, label: project.name }));
    }
    if (contextType === "BATCH_JOB") {
      return (batches.data ?? []).map((batch) => ({
        id: batch.id,
        label: `批量 ${batch.id.slice(0, 8)} · ${batch.completedItems}/${batch.totalItems}`,
      }));
    }
    return [];
  }, [batches.data, contextType, projects.data]);
  useEffect(() => {
    if (contextType === "WORKSPACE") setContextId("");
    else if (!contextOptions.some((option) => option.id === contextId)) setContextId(contextOptions[0]?.id ?? "");
  }, [contextId, contextOptions, contextType]);

  const createMutation = useMutation({
    mutationFn: () => createAgentSession({
      projectId: contextType === "PROJECT" ? contextId : undefined,
      batchJobId: contextType === "BATCH_JOB" ? contextId : undefined,
    }),
    onSuccess: async (created) => {
      setActiveSessionId(created.id);
      queryClient.setQueryData(["agent-session", created.id], created);
      await queryClient.invalidateQueries({ queryKey: ["agent-sessions"] });
    },
  });
  const archiveMutation = useMutation({
    mutationFn: (id: string) => archiveAgentSession(id),
    onSuccess: async (_, id) => {
      setActiveSessionId((current) => current === id ? undefined : current);
      await queryClient.invalidateQueries({ queryKey: ["agent-sessions"] });
    },
  });
  const proposalMutation = useMutation({
    mutationFn: (input: Parameters<typeof proposeAgentAction>[0]) => proposeAgentAction(input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["agent-actions", activeSessionId] });
    },
  });
  const confirmMutation = useMutation({
    mutationFn: (id: string) => confirmAgentAction(id),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["agent-actions", activeSessionId] }),
        queryClient.invalidateQueries({ queryKey: ["batch-jobs"] }),
        queryClient.invalidateQueries({ queryKey: ["reports"] }),
        queryClient.invalidateQueries({ queryKey: ["projects"] }),
      ]);
    },
  });
  const rejectMutation = useMutation({
    mutationFn: (id: string) => rejectAgentAction(id),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ["agent-actions", activeSessionId] }),
  });

  return (
    <main className="page agent-page">
      <AppTopbar title={RS_BOT_NAME} subtitle={RS_BOT_SUBTITLE} />
      <div className="agent-workbench">
        <aside className="agent-session-rail" aria-label="Agent 分析会话">
          <div className="agent-rail-heading">
            <div><StatusBadge>可信编排</StatusBadge><h2>分析会话</h2></div>
          </div>
          <div className="agent-context-builder">
            <label>
              <span>分析范围</span>
              <select value={contextType} onChange={(event) => setContextType(event.target.value as ContextType)}>
                <option value="PROJECT">项目</option>
                <option value="BATCH_JOB">批量任务</option>
                <option value="WORKSPACE">整个工作区</option>
              </select>
              <ChevronDown size={14} />
            </label>
            {contextType !== "WORKSPACE" && (
              <label>
                <span>{contextType === "PROJECT" ? "选择项目" : "选择任务"}</span>
                <select value={contextId} onChange={(event) => setContextId(event.target.value)}>
                  {contextOptions.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
                </select>
                <ChevronDown size={14} />
              </label>
            )}
            <button
              className="primary-button"
              type="button"
              disabled={createMutation.isPending || (contextType !== "WORKSPACE" && !contextId)}
              onClick={() => createMutation.mutate()}
            >
              {createMutation.isPending ? <LoaderCircle className="spin" size={15} /> : <Plus size={15} />}新建分析会话
            </button>
          </div>
          <div className="agent-session-list">
            {(sessions.data ?? []).map((item) => (
              <div className={`agent-session-row ${item.id === activeSessionId ? "is-active" : ""}`} key={item.id}>
                <button type="button" onClick={() => setActiveSessionId(item.id)}>
                  <Workflow size={15} />
                  <span><strong>{item.title}</strong><small>{item.contextLabel} · {item.runCount} 轮</small></span>
                </button>
                <button className="icon-button" type="button" aria-label={`归档 ${item.title}`} onClick={() => archiveMutation.mutate(item.id)}><Archive size={14} /></button>
              </div>
            ))}
            {sessions.isSuccess && sessions.data.length === 0 && (
              <div className="agent-empty-list"><Sparkles size={18} /><p>选择分析范围并建立第一条可信会话。</p></div>
            )}
          </div>
          <div className="agent-rail-boundary"><ShieldCheck size={15} /><p>统计由 Java/SQL 确定性计算；Agent 不会自行编造数量。</p></div>
        </aside>

        <section className="agent-conversation">
          {!activeSessionId ? (
            <AgentWelcome />
          ) : rsBot.isLoadingSession ? (
            <div className="agent-center-state"><LoaderCircle className="spin" size={20} />恢复分析上下文…</div>
          ) : rsBot.sessionError ? (
            <div className="agent-center-state error"><AlertTriangle size={20} />{rsBot.sessionError}</div>
          ) : rsBot.session ? (
            <>
              <AgentHeader
                session={rsBot.session}
                pendingActions={pendingActions.length}
                actionPanelOpen={actionPanelOpen}
                onToggleActionPanel={() => setActionPanelOpen((open) => !open)}
              />
              {/* Controlled actions are opt-in: they only take space when the
                  user opens them or when something is actually waiting. */}
              <AnimatePresence initial={false}>
                {showActionPanel && (
                  <AgentActionCenter
                    session={rsBot.session}
                    actions={actions.data ?? []}
                    reports={reports.data ?? []}
                    disabled={proposalMutation.isPending || confirmMutation.isPending || rejectMutation.isPending}
                    onPropose={(input) => proposalMutation.mutate(input)}
                    onConfirm={(id) => confirmMutation.mutate(id)}
                    onReject={(id) => rejectMutation.mutate(id)}
                  />
                )}
              </AnimatePresence>
              <RsBotChat
                runs={rsBot.session.runs}
                isRunning={rsBot.isRunning}
                stage={rsBot.stage}
                pendingQuestion={rsBot.pendingQuestion}
                error={rsBot.error}
                placeholder={`分析“${rsBot.session.contextLabel}”中的模型事实、分布和复核线索…`}
                suggestions={rsBot.session.suggestedPrompts}
                onAsk={rsBot.ask}
                onCancel={rsBot.cancel}
              />
              {(proposalMutation.isError || confirmMutation.isError || rejectMutation.isError) && (
                <div className="agent-inline-error"><AlertTriangle size={14} />{(proposalMutation.error ?? confirmMutation.error ?? rejectMutation.error)?.message}</div>
              )}
              <p className="agent-composer-note"><ShieldCheck size={12} />工具结果是事实来源；写操作需要你确认后才会执行，原始影像不会默认外发。</p>
            </>
          ) : null}
        </section>
      </div>
    </main>
  );
}

function AgentWelcome() {
  return (
    <div className="agent-welcome">
      <span><Sparkles size={25} /></span>
      <p className="eyebrow">TRACEABLE WORKFLOW AGENT</p>
      <h1>让分析建立在可核验事实之上</h1>
      <p>绑定项目或批量任务后，Agent 可以读取确定性统计、模型版本、失败记录、知识引用和报告事实包。</p>
      <div><span><Database size={16} />后端确定性统计</span><span><FileSearch size={16} />带引用知识检索</span><span><ShieldCheck size={16} />来源与边界分离</span></div>
    </div>
  );
}

function AgentActionCenter({
  session,
  actions,
  reports,
  disabled,
  onPropose,
  onConfirm,
  onReject,
}: {
  session: AgentSession;
  actions: AgentActionProposal[];
  reports: ReportSummary[];
  disabled: boolean;
  onPropose: (input: Parameters<typeof proposeAgentAction>[0]) => void;
  onConfirm: (id: string) => void;
  onReject: (id: string) => void;
}) {
  const [actionName, setActionName] = useState<AgentActionName>(defaultAction(session.contextType));
  const [questions, setQuestions] = useState("图中有没有道路？\n图中有多少建筑物？");
  const [title, setTitle] = useState("");
  const [reportId, setReportId] = useState("");
  const [format, setFormat] = useState<"md" | "json">("md");
  const options = actionOptions(session.contextType);
  const scopedReports = reports.filter((report) => (
    session.contextType === "PROJECT" ? report.projectId === session.contextId :
      session.contextType === "BATCH_JOB" ? report.batchJobId === session.contextId : true
  ));
  useEffect(() => {
    if (!options.some((option) => option.value === actionName)) setActionName(options[0]?.value ?? "save_report_draft");
  }, [actionName, options]);
  useEffect(() => {
    if (!reportId || !scopedReports.some((report) => report.id === reportId)) setReportId(scopedReports[0]?.id ?? "");
  }, [reportId, scopedReports]);
  const submit = () => {
    const input: Parameters<typeof proposeAgentAction>[0] = {
      sessionId: session.id,
      actionName,
      projectId: session.contextType === "PROJECT" ? session.contextId ?? undefined : undefined,
      conversationId: session.contextType === "CONVERSATION" ? session.contextId ?? undefined : undefined,
      batchJobId: session.contextType === "BATCH_JOB" ? session.contextId ?? undefined : undefined,
      title: title.trim() || undefined,
    };
    if (actionName === "create_batch_task") input.questions = questions.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
    if (actionName === "export_report") {
      input.reportId = reportId || undefined;
      input.format = format;
    }
    onPropose(input);
  };
  return (
    <motion.section
      className="agent-action-center"
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: "auto" }}
      exit={{ opacity: 0, height: 0 }}
      transition={{ duration: 0.18 }}
    >
      <div className="agent-action-heading">
        <div><span className="agent-action-kicker"><ShieldCheck size={13} />受控操作</span><strong>需要副作用时，先提交提案再人工确认</strong></div>
        <small>每次操作都有稳定 request ID 和审计记录</small>
      </div>
      {options.length > 0 && (
        <div className="agent-action-form">
          <label><span>选择操作</span><select aria-label="选择受控操作" value={actionName} onChange={(event) => setActionName(event.target.value as AgentActionName)}>
            {options.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}
          </select></label>
          {actionName === "create_batch_task" && <label className="agent-action-wide"><span>问题模板（每行一个）</span><textarea aria-label="批量任务问题" value={questions} onChange={(event) => setQuestions(event.target.value)} rows={2} maxLength={1200} /></label>}
          {(actionName === "save_report_draft" || actionName === "create_batch_task") && <label><span>可选标题</span><input aria-label="操作标题" value={title} onChange={(event) => setTitle(event.target.value)} maxLength={200} placeholder="留空使用系统标题" /></label>}
          {actionName === "export_report" && <>
            <label><span>报告</span><select aria-label="选择导出报告" value={reportId} onChange={(event) => setReportId(event.target.value)} disabled={scopedReports.length === 0}>
              {scopedReports.length === 0 ? <option value="">当前范围暂无报告</option> : scopedReports.map((report) => <option value={report.id} key={report.id}>{report.title}</option>)}
            </select></label>
            <label><span>格式</span><select aria-label="选择导出格式" value={format} onChange={(event) => setFormat(event.target.value as "md" | "json")}><option value="md">Markdown</option><option value="json">JSON</option></select></label>
          </>}
          <button className="quiet-button agent-action-submit" type="button" disabled={disabled || (actionName === "export_report" && !reportId)} onClick={submit}><FileText size={14} />提交操作提案</button>
        </div>
      )}
      {actions.length > 0 && <div className="agent-proposal-list" aria-label="受控操作提案">
        {actions.slice(0, 5).map((proposal) => <AgentProposalCard key={proposal.id} proposal={proposal} disabled={disabled} onConfirm={onConfirm} onReject={onReject} />)}
      </div>}
    </motion.section>
  );
}

function AgentProposalCard({
  proposal,
  disabled,
  onConfirm,
  onReject,
}: {
  proposal: AgentActionProposal;
  disabled: boolean;
  onConfirm: (id: string) => void;
  onReject: (id: string) => void;
}) {
  let result: Record<string, unknown> = {};
  try { result = proposal.resultJson ? JSON.parse(proposal.resultJson) as Record<string, unknown> : {}; } catch { /* sanitized fallback */ }
  const downloadUrl = typeof result.downloadUrl === "string" ? result.downloadUrl : undefined;
  return (
    <article className={`agent-proposal-card is-${proposal.status.toLowerCase()}`}>
      <div className="agent-proposal-status"><span><Clock3 size={14} />{proposalStatus(proposal.status)}</span><small>{proposal.actionName}</small></div>
      <strong>{proposal.summary}</strong>
      <p>请求 <code>{proposal.requestId}</code> · {proposal.providerId} · {proposal.totalTokens} tokens · ${proposal.estimatedCostUsd.toFixed(4)}</p>
      {proposal.status === "PENDING" && <div className="agent-proposal-actions"><button className="primary-button" type="button" disabled={disabled} onClick={() => onConfirm(proposal.id)}><Check size={14} />确认执行</button><button className="quiet-button" type="button" disabled={disabled} onClick={() => onReject(proposal.id)}><X size={14} />拒绝</button></div>}
      {proposal.status === "FAILED" && <p className="agent-proposal-error"><AlertTriangle size={13} />{proposal.errorCode ?? "执行失败"}</p>}
      {downloadUrl && <a className="quiet-button" href={downloadUrl}><FileText size={14} />下载导出文件</a>}
    </article>
  );
}

function actionOptions(contextType: AgentSession["contextType"]): Array<{ value: AgentActionName; label: string }> {
  if (contextType === "PROJECT") return [
    { value: "create_batch_task", label: "从项目影像创建批量任务" },
    { value: "save_report_draft", label: "保存项目报告草稿" },
    { value: "export_report", label: "导出项目报告" },
    { value: "archive_project", label: "归档项目" },
  ];
  if (contextType === "BATCH_JOB") return [
    { value: "retry_batch_failures", label: "重试批量失败项" },
    { value: "save_report_draft", label: "保存批量报告草稿" },
    { value: "export_report", label: "导出批量报告" },
    { value: "archive_batch_task", label: "归档批量任务" },
  ];
  if (contextType === "CONVERSATION") return [{ value: "archive_conversation", label: "归档当前对话" }];
  return [];
}

function defaultAction(contextType: AgentSession["contextType"]): AgentActionName {
  return contextType === "BATCH_JOB" ? "retry_batch_failures" : contextType === "CONVERSATION" ? "archive_conversation" : "create_batch_task";
}

function proposalStatus(status: AgentActionProposal["status"]) {
  return ({ PENDING: "待人工确认", EXECUTING: "正在执行", COMPLETED: "已完成", FAILED: "执行失败", REJECTED: "已拒绝", EXPIRED: "已过期" } as Record<string, string>)[status] ?? status;
}

function AgentHeader({
  session,
  pendingActions,
  actionPanelOpen,
  onToggleActionPanel,
}: {
  session: AgentSession;
  pendingActions: number;
  actionPanelOpen: boolean;
  onToggleActionPanel: () => void;
}) {
  return (
    <header className="agent-context-header">
      <div className="agent-context-identity">
        <span><Workflow size={15} />{contextTypeLabel(session.contextType)}</span>
        <h1 title={session.title}>{session.title}</h1>
        <p title={session.contextLabel}>{session.contextLabel}</p>
      </div>
      <button
        className={`quiet-button agent-action-toggle ${actionPanelOpen || pendingActions > 0 ? "is-active" : ""}`}
        type="button"
        aria-expanded={actionPanelOpen || pendingActions > 0}
        onClick={onToggleActionPanel}
      >
        <ShieldCheck size={14} />受控操作
        {pendingActions > 0 && <span className="agent-action-badge">{pendingActions}</span>}
      </button>
    </header>
  );
}

function contextTypeLabel(value: AgentSession["contextType"]) {
  if (value === "PROJECT") return "项目分析";
  if (value === "BATCH_JOB") return "批量任务分析";
  if (value === "CONVERSATION") return "会话分析";
  return "工作区分析";
}
