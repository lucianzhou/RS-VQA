import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  Database,
  Info,
  Send,
  Square,
} from "lucide-react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import {
  RS_BOT_PROGRESS_STEPS,
  RULE_BASED_NOTICE,
  rsBotProgress,
  stopReasonLabel,
  type RsBotStage,
} from "../rsbot";
import type { AgentHistoryRun, AgentToolCall } from "../types";
import { SafeMarkdown } from "./SafeMarkdown";

/**
 * RS-Bot's conversation surface.
 *
 * <p>Used verbatim by the workspace drawer and by the standalone page so the two
 * cannot drift into different agents. Layout differences are the caller's job
 * (width, chrome); everything about how a turn reads belongs here.
 */
export function RsBotChat({
  runs,
  isRunning,
  stage,
  pendingQuestion,
  error,
  placeholder,
  suggestions = [],
  compact = false,
  onAsk,
  onCancel,
}: {
  runs: AgentHistoryRun[];
  isRunning: boolean;
  stage: RsBotStage;
  pendingQuestion: string;
  error?: string;
  placeholder: string;
  suggestions?: string[];
  /** Drawer variant: tighter spacing and smaller type. */
  compact?: boolean;
  onAsk: (message: string) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState("");
  const transcript = useRef<HTMLDivElement | null>(null);
  const reduceMotion = useReducedMotion();
  const progress = rsBotProgress(stage);
  const activeProgressIndex = progress.activeIndex;

  // Keep the newest turn in view without stealing focus from the composer.
  useEffect(() => {
    const node = transcript.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [runs.length, isRunning, pendingQuestion]);

  const submit = () => {
    const value = draft.trim();
    if (!value || isRunning) return;
    onAsk(value);
    setDraft("");
  };

  return (
    <div className={`rsbot-chat ${compact ? "is-compact" : ""}`}>
      <div className="rsbot-transcript" ref={transcript} aria-live="polite" aria-label="RS-Bot 对话记录">
        {runs.length === 0 && !isRunning && (
          <div className="rsbot-empty">
            <span className="rsbot-avatar"><Bot size={compact ? 15 : 18} /></span>
            <p>提问后，RS-Bot 会调用已授权的只读工具，并基于工具返回的事实回答。</p>
          </div>
        )}
        {runs.map((run) => <RsBotTurn key={run.runId} run={run} compact={compact} />)}
        <AnimatePresence initial={false}>
          {isRunning && (
            <motion.div
              className="rsbot-turn"
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
            >
              {pendingQuestion && <article className="rsbot-question"><p>{pendingQuestion}</p></article>}
              <article className="rsbot-answer is-pending">
                <span className="rsbot-avatar"><Bot size={compact ? 15 : 18} /></span>
                <div
                  className="rsbot-progress-status"
                  role="status"
                  aria-live="polite"
                  aria-atomic="true"
                  aria-label={`RS-Bot ${progress.label}`}
                >
                  <div aria-hidden="true">
                    <span className="rsbot-stage-label">
                      <AnimatePresence mode="sync" initial={false}>
                        <motion.strong
                          key={progress.key}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          transition={{ duration: reduceMotion ? 0.1 : 0.14, ease: [0.23, 1, 0.32, 1] }}
                        >
                          {progress.label}
                        </motion.strong>
                      </AnimatePresence>
                    </span>
                    {activeProgressIndex == null ? (
                      <span className="rsbot-stage-neutral">处理中</span>
                    ) : (
                      <>
                        <ol className="rsbot-stage-track">
                          {RS_BOT_PROGRESS_STEPS.map((label, index) => (
                            <li
                              className={index < activeProgressIndex
                                ? "is-complete"
                                : index === activeProgressIndex ? "is-active" : ""}
                              key={label}
                            >
                              <span>{index < activeProgressIndex ? <CheckCircle2 size={11} /> : index + 1}</span>
                              <small>{label}</small>
                            </li>
                          ))}
                        </ol>
                        <span className="rsbot-progress-line" />
                      </>
                    )}
                  </div>
                </div>
              </article>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {error && <p className="rsbot-error" role="alert"><AlertTriangle size={13} />{error}</p>}

      {suggestions.length > 0 && runs.length === 0 && !isRunning && (
        <div className="rsbot-suggestions">
          {suggestions.slice(0, 4).map((item) => (
            <button type="button" key={item} onClick={() => onAsk(item)}>{item}</button>
          ))}
        </div>
      )}

      <form
        className="rsbot-composer"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <label className="sr-only" htmlFor={compact ? "rsbot-drawer-input" : "rsbot-page-input"}>
          向 RS-Bot 提问
        </label>
        <textarea
          id={compact ? "rsbot-drawer-input" : "rsbot-page-input"}
          aria-label="向 RS-Bot 提问"
          rows={compact ? 2 : 2}
          maxLength={500}
          value={draft}
          placeholder={placeholder}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
        />
        {isRunning ? (
          <button type="button" aria-label="停止 RS-Bot" onClick={onCancel}><Square size={15} /></button>
        ) : (
          <button type="submit" aria-label="发送给 RS-Bot" disabled={!draft.trim()}><Send size={15} /></button>
        )}
      </form>
    </div>
  );
}

function RsBotTurn({ run, compact }: { run: AgentHistoryRun; compact: boolean }) {
  const ruleBased = run.providerState === "RULE_BASED_TOOLS";
  const stopNote = stopReasonLabel(run.stopReason);
  return (
    <motion.div className="rsbot-turn" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}>
      <article className="rsbot-question"><p>{run.input}</p></article>
      <article className="rsbot-answer">
        <span className="rsbot-avatar"><Bot size={compact ? 15 : 18} /></span>
        <div>
          <div className="rsbot-answer-meta">
            <span><CheckCircle2 size={12} />基于工具事实</span>
            {run.latencyMs != null && <small>{run.latencyMs} ms</small>}
            {run.toolSteps != null && run.toolSteps > 1 && <small>{run.toolSteps} 步</small>}
          </div>
          {ruleBased && <p className="rsbot-mode-note"><Info size={12} />{RULE_BASED_NOTICE}</p>}
          {stopNote && <p className="rsbot-mode-note"><AlertTriangle size={12} />{stopNote}</p>}
          <RsBotMarkdown content={run.answer} />
          {run.toolCalls.length > 0 && (
            <div className="rsbot-tools">
              {run.toolCalls.map((call) => <RsBotToolCall key={call.id} call={call} traceId={run.traceId} />)}
            </div>
          )}
          <small className="rsbot-trace">
            Trace {run.traceId}
            {run.providerModel ? ` · ${run.providerModel}` : ""}
            {run.promptVersion ? ` · ${run.promptVersion}` : ""}
          </small>
        </div>
      </article>
    </motion.div>
  );
}

export function RsBotMarkdown({ content }: { content: string | null }) {
  return <SafeMarkdown className="rsbot-answer-text" content={content} />;
}

function RsBotToolCall({ call, traceId }: { call: AgentToolCall; traceId: string }) {
  const rejected = call.status === "REJECTED";
  const failed = call.status === "FAILED";
  return (
    <details className={`rsbot-tool ${rejected ? "is-rejected" : failed ? "is-failed" : ""}`}>
      <summary>
        <span><Database size={12} /><strong>{toolLabel(call.name)}</strong></span>
        <small>{toolStatusLabel(call.status)} · {call.latencyMs} ms</small>
      </summary>
      <div className="rsbot-tool-detail">
        <dl>
          <div><dt>参数</dt><dd>{call.inputSummary || "{}"}</dd></div>
          <div><dt>Trace</dt><dd>{traceId}</dd></div>
        </dl>
        <pre>{call.output}</pre>
      </div>
    </details>
  );
}

function toolStatusLabel(status: string) {
  return ({
    COMPLETED: "已完成",
    REJECTED: "已拒绝",
    FAILED: "失败",
    RUNNING: "执行中",
  } as Record<string, string>)[status] ?? status;
}

export function toolLabel(value: string) {
  return ({
    current_model_release: "当前模型发布",
    supported_question_types: "模型能力边界",
    model_capabilities: "模型能力边界",
    system_health: "系统健康状态",
    conversation_history: "会话历史",
    conversation_vqa_results: "会话 VQA 结果",
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
    knowledge_search: "知识库检索",
    single_image_vqa: "受控单图 VQA",
  } as Record<string, string>)[value] ?? value;
}
