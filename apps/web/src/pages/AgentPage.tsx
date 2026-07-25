import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  Archive,
  Bot,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Database,
  FileSearch,
  FileText,
  LoaderCircle,
  Plus,
  Send,
  ShieldCheck,
  Sparkles,
  Square,
  Check,
  X,
  Workflow,
} from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import {
  archiveAgentSession,
  createAgentSession,
  getAgentSession,
  confirmAgentAction,
  listAgentSessions,
  listAgentActions,
  listBatchJobs,
  listReports,
  listProjects,
  proposeAgentAction,
  rejectAgentAction,
  runTrustedAgentStream,
} from "../api";
import { AppTopbar, StatusBadge } from "../components/AppChrome";
import type { AgentActionName, AgentActionProposal, AgentHistoryRun, AgentSession, AgentToolCall, ReportSummary } from "../types";

type ContextType = "WORKSPACE" | "PROJECT" | "BATCH_JOB";

export function AgentPage() {
  const queryClient = useQueryClient();
  const [activeSessionId, setActiveSessionId] = useState<string>();
  const [composer, setComposer] = useState("");
  const [stage, setStage] = useState("");
  const [controller, setController] = useState<AbortController>();
  const [contextType, setContextType] = useState<ContextType>("PROJECT");
  const [contextId, setContextId] = useState("");
  const sessions = useQuery({ queryKey: ["agent-sessions"], queryFn: listAgentSessions });
  const projects = useQuery({ queryKey: ["projects"], queryFn: listProjects });
  const batches = useQuery({ queryKey: ["batch-jobs"], queryFn: listBatchJobs });
  const reports = useQuery({ queryKey: ["reports"], queryFn: listReports });
  const session = useQuery({
    queryKey: ["agent-session", activeSessionId],
    queryFn: () => getAgentSession(activeSessionId!),
    enabled: Boolean(activeSessionId),
  });
  const actions = useQuery({
    queryKey: ["agent-actions", activeSessionId],
    queryFn: () => listAgentActions(activeSessionId),
    enabled: Boolean(activeSessionId),
  });

  useEffect(() => {
    if (!activeSessionId && sessions.data?.[0]) setActiveSessionId(sessions.data[0].id);
  }, [activeSessionId, sessions.data]);
  useEffect(() => () => controller?.abort(), [controller]);

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
  const runMutation = useMutation({
    mutationFn: ({ message, signal }: { message: string; signal: AbortSignal }) => runTrustedAgentStream(
      { message, sessionId: activeSessionId },
      setStage,
      signal,
    ),
    onSuccess: async () => {
      setComposer("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["agent-session", activeSessionId] }),
        queryClient.invalidateQueries({ queryKey: ["agent-sessions"] }),
        queryClient.invalidateQueries({ queryKey: ["audit"] }),
      ]);
    },
    onSettled: () => setController(undefined),
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

  const submit = (message: string) => {
    const value = message.trim();
    if (!value || !activeSessionId || runMutation.isPending) return;
    const requestController = new AbortController();
    setController(requestController);
    setStage("accepted");
    runMutation.mutate({ message: value, signal: requestController.signal });
  };

  return (
    <main className="page agent-page">
      <AppTopbar title="可信 Agent" subtitle="项目分析与可追溯工作流协作" />
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
          ) : session.isPending ? (
            <div className="agent-center-state"><LoaderCircle className="spin" size={20} />恢复分析上下文…</div>
          ) : session.isError ? (
            <div className="agent-center-state error"><AlertTriangle size={20} />{session.error.message}</div>
          ) : session.data ? (
            <>
              <AgentHeader session={session.data} />
              <AgentActionCenter
                session={session.data}
                actions={actions.data ?? []}
                reports={reports.data ?? []}
                disabled={proposalMutation.isPending || confirmMutation.isPending || rejectMutation.isPending}
                onPropose={(input) => proposalMutation.mutate(input)}
                onConfirm={(id) => confirmMutation.mutate(id)}
                onReject={(id) => rejectMutation.mutate(id)}
              />
              <div className="agent-thread" aria-live="polite">
                {session.data.runs.length === 0 && (
                  <section className="agent-starter">
                    <span className="agent-orbit"><Bot size={22} /></span>
                    <h2>从真实业务事实开始分析</h2>
                    <p>选择一个建议，Agent 会调用已授权工具读取项目、批任务、模型版本或知识来源。</p>
                    <div>{session.data.suggestedPrompts.map((prompt) => <button type="button" key={prompt} onClick={() => submit(prompt)}>{prompt}</button>)}</div>
                  </section>
                )}
                <AnimatePresence initial={false}>
                  {session.data.runs.map((run) => <AgentRunBlock key={run.runId} run={run} />)}
                  {runMutation.isPending && (
                    <motion.article className="agent-running" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
                      <span className="agent-avatar"><Bot size={16} /></span>
                      <div><strong>{stage === "tool_started" ? "正在执行确定性工具" : "正在建立受控执行"}</strong><p>原始模型结果和数据库事实不会被改写。</p><span className="agent-progress-line" /></div>
                    </motion.article>
                  )}
                </AnimatePresence>
              </div>
              <div className="agent-suggestion-strip">
                {session.data.suggestedPrompts.slice(0, 3).map((prompt) => <button type="button" key={prompt} disabled={runMutation.isPending} onClick={() => setComposer(prompt)}>{prompt}</button>)}
              </div>
              {runMutation.isError && <div className="agent-inline-error"><AlertTriangle size={14} />{runMutation.error.message}</div>}
              {(proposalMutation.isError || confirmMutation.isError || rejectMutation.isError) && (
                <div className="agent-inline-error"><AlertTriangle size={14} />{(proposalMutation.error ?? confirmMutation.error ?? rejectMutation.error)?.message}</div>
              )}
              <form className="agent-composer" onSubmit={(event) => {
                event.preventDefault();
                submit(composer);
              }}>
                <textarea
                  aria-label="向可信 Agent 提问"
                  rows={2}
                  maxLength={500}
                  value={composer}
                  onChange={(event) => setComposer(event.target.value)}
                  placeholder={`分析“${session.data.contextLabel}”中的模型事实、分布和复核线索…`}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" && !event.shiftKey) {
                      event.preventDefault();
                      submit(composer);
                    }
                  }}
                />
                {runMutation.isPending ? (
                  <button type="button" aria-label="停止 Agent" onClick={() => controller?.abort()}><Square size={16} /></button>
                ) : (
                  <button type="submit" aria-label="发送给可信 Agent" disabled={!composer.trim()}><Send size={16} /></button>
                )}
              </form>
              <p className="agent-composer-note"><ShieldCheck size={12} />外部模型未配置时仍可执行全部确定性分析工具；原始影像不会默认外发。</p>
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
    <motion.section className="agent-action-center" initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }}>
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

function AgentHeader({ session }: { session: AgentSession }) {
  return (
    <header className="agent-context-header">
      <div><span><Workflow size={15} />{contextTypeLabel(session.contextType)}</span><h1>{session.title}</h1><p>{session.contextLabel}</p></div>
      <div><span className="agent-provider-state">确定性工具可用</span><span>外部模型未配置时不参与生成</span></div>
    </header>
  );
}

function AgentRunBlock({ run }: { run: AgentHistoryRun }) {
  return (
    <motion.div className="agent-turn" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
      <article className="agent-user-message"><p>{run.input}</p></article>
      <article className="agent-answer">
        <span className="agent-avatar"><Bot size={16} /></span>
        <div>
          <div className="agent-answer-meta"><span><CheckCircle2 size={13} />可信工具解释</span><small>{run.latencyMs ?? 0} ms</small></div>
          <p>{run.answer}</p>
          <div className="agent-tool-timeline">
            {run.toolCalls.map((call) => <ToolTimelineItem key={call.id} call={call} traceId={run.traceId} />)}
          </div>
          <small className="agent-trace">Trace {run.traceId}</small>
        </div>
      </article>
    </motion.div>
  );
}

function ToolTimelineItem({ call, traceId }: { call: AgentToolCall; traceId: string }) {
  return (
    <details className="agent-tool-card">
      <summary>
        <span><span className="tool-status-dot" /><Database size={14} /><strong>{toolLabel(call.name)}</strong></span>
        <small>{call.status} · {call.latencyMs} ms</small>
      </summary>
      <div className="agent-tool-detail">
        <dl><div><dt>参数摘要</dt><dd>{call.inputSummary || "{}"}</dd></div><div><dt>请求追踪</dt><dd>{traceId}</dd></div></dl>
        <ToolFacts output={call.output} />
      </div>
    </details>
  );
}

function ToolFacts({ output }: { output: string }) {
  let value: Record<string, unknown> | null = null;
  try {
    value = JSON.parse(output) as Record<string, unknown>;
  } catch {
    // Render a sanitized text snapshot below.
  }
  if (!value) return <pre>{output}</pre>;
  const facts = [
    ["会话", value.conversationCount],
    ["图像", value.imageCount],
    ["问题", value.questionCount],
    ["已回答", value.answeredCount],
    ["需复核", value.lowConfidenceCount],
    ["失败", value.failedCount],
  ].filter(([, item]) => typeof item === "number");
  return (
    <>
      {facts.length > 0 && <div className="agent-fact-grid">{facts.map(([label, item]) => <span key={String(label)}><strong>{String(item)}</strong>{String(label)}</span>)}</div>}
      <details className="agent-raw-facts"><summary>查看结构化工具输出</summary><pre>{JSON.stringify(value, null, 2)}</pre></details>
    </>
  );
}

function contextTypeLabel(value: AgentSession["contextType"]) {
  if (value === "PROJECT") return "项目分析";
  if (value === "BATCH_JOB") return "批量任务分析";
  if (value === "CONVERSATION") return "会话分析";
  return "工作区分析";
}

function toolLabel(value: string) {
  return ({
    current_model_release: "当前模型发布",
    supported_question_types: "模型能力边界",
    system_health: "系统健康状态",
    conversation_history: "会话历史",
    conversation_vqa_results: "会话 VQA 结果",
    model_capabilities: "模型能力边界",
    project_summary: "项目摘要",
    project_conversations: "项目会话",
    project_vqa_statistics: "项目 VQA 统计",
    batch_job_status: "批量任务状态",
    batch_result_statistics: "批量结果统计",
    confidence_distribution: "置信度分布",
    unsupported_question_summary: "超范围问题汇总",
    failed_invocation_summary: "失败调用汇总",
    audit_lookup: "审计检索",
    create_batch_plan: "批量任务规划",
    report_draft_data: "报告事实包",
    search_knowledge: "知识库检索",
    single_image_vqa: "受控单图 VQA",
  } as Record<string, string>)[value] ?? value;
}
