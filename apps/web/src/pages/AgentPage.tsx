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
  LoaderCircle,
  Plus,
  Send,
  ShieldCheck,
  Sparkles,
  Square,
  Workflow,
} from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import {
  archiveAgentSession,
  createAgentSession,
  getAgentSession,
  listAgentSessions,
  listBatchJobs,
  listProjects,
  runTrustedAgentStream,
} from "../api";
import { AppTopbar, StatusBadge } from "../components/AppChrome";
import type { AgentHistoryRun, AgentSession, AgentToolCall } from "../types";

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
  const session = useQuery({
    queryKey: ["agent-session", activeSessionId],
    queryFn: () => getAgentSession(activeSessionId!),
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

function AgentHeader({ session }: { session: AgentSession }) {
  return (
    <header className="agent-context-header">
      <div><span><Workflow size={15} />{contextTypeLabel(session.contextType)}</span><h1>{session.title}</h1><p>{session.contextLabel}</p></div>
      <div><span className="agent-provider-state">确定性工具可用</span><span>Gemini 未配置时不参与生成</span></div>
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
    project_vqa_statistics: "项目 VQA 统计",
    batch_job_status: "批量任务状态",
    batch_result_statistics: "批量结果统计",
    report_draft_data: "报告事实包",
    search_knowledge: "知识库检索",
    single_image_vqa: "受控单图 VQA",
  } as Record<string, string>)[value] ?? value;
}
