import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { createAgentSession, getAgentSession, runTrustedAgentStream } from "./api";
import type { AgentHistoryRun, AgentRun, AgentSession } from "./types";

/** User-facing product name. Kept in one place so the two surfaces cannot drift. */
export const RS_BOT_NAME = "RS-Bot";
export const RS_BOT_SUBTITLE = "可信遥感分析助手";

/**
 * Shown when no planning model is configured. Users need to know planning is
 * off, not to decode a status token.
 */
export const RULE_BASED_NOTICE = "RS-Bot 当前处于规则工具模式，未启用智能规划";

export type RsBotStage = "" | "accepted" | "tool_started" | "completed" | "failed";

export interface RsBotContext {
  sessionId?: string;
  projectId?: string;
  conversationId?: string;
  batchJobId?: string;
}

/**
 * One RS-Bot conversation, shared by the workspace drawer and the standalone
 * page.
 *
 * <p>Both surfaces talk to the same session, the same run history and the same
 * streaming endpoint. Keeping that here is what stops them becoming two agents
 * that disagree about what was asked.
 */
export function useRsBotSession(context: RsBotContext) {
  const queryClient = useQueryClient();
  const [stage, setStage] = useState<RsBotStage>("");
  const [pendingQuestion, setPendingQuestion] = useState("");
  const controller = useRef<AbortController | undefined>(undefined);

  const session = useQuery({
    queryKey: ["agent-session", context.sessionId],
    queryFn: () => getAgentSession(context.sessionId!),
    enabled: Boolean(context.sessionId),
  });

  useEffect(() => () => controller.current?.abort(), []);

  const run = useMutation({
    mutationFn: ({ message, signal }: { message: string; signal: AbortSignal }) =>
      runTrustedAgentStream({ message, ...context }, (event) => setStage(event as RsBotStage), signal),
    onSettled: async () => {
      controller.current = undefined;
      setPendingQuestion("");
      setStage("");
      if (context.sessionId) {
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ["agent-session", context.sessionId] }),
          queryClient.invalidateQueries({ queryKey: ["agent-sessions"] }),
          queryClient.invalidateQueries({ queryKey: ["agent-actions", context.sessionId] }),
          queryClient.invalidateQueries({ queryKey: ["audit"] }),
        ]);
      }
    },
  });

  const ask = useCallback((message: string) => {
    const value = message.trim();
    if (!value || run.isPending) return;
    const next = new AbortController();
    controller.current = next;
    setPendingQuestion(value);
    setStage("accepted");
    run.mutate({ message: value, signal: next.signal });
  }, [run]);

  const cancel = useCallback(() => {
    controller.current?.abort();
    controller.current = undefined;
    setStage("");
  }, []);

  return {
    session: session.data,
    isLoadingSession: session.isPending && Boolean(context.sessionId),
    sessionError: session.error?.message,
    /** Persisted turns; the drawer keeps the last transient run when unbound. */
    runs: session.data?.runs ?? [],
    lastRun: run.data,
    isRunning: run.isPending,
    error: run.error?.message,
    stage,
    pendingQuestion,
    ask,
    cancel,
  };
}

/** Creates a session bound to the given context and returns its id. */
export function useCreateRsBotSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (context: Omit<RsBotContext, "sessionId">) => createAgentSession(context),
    onSuccess: async (created: AgentSession) => {
      queryClient.setQueryData(["agent-session", created.id], created);
      await queryClient.invalidateQueries({ queryKey: ["agent-sessions"] });
    },
  });
}

export function stageLabel(stage: RsBotStage): string {
  if (stage === "tool_started") return "正在执行工具";
  if (stage === "accepted") return "正在建立受控执行";
  return "正在分析";
}

export function stopReasonLabel(reason?: string | null): string | null {
  if (!reason) return null;
  return ({
    completed: "",
    rule_based_single_tool: "",
    max_steps_reached: "已达到最大工具步数",
    timeout: "超出时间预算",
    token_budget_exhausted: "超出 token 预算",
    cancelled: "已取消",
    empty_response: "模型未返回结论",
  } as Record<string, string>)[reason] ?? reason;
}

/** Turns a completed run into the shape the transcript renders. */
export function toTranscriptRun(run: AgentRun, input: string): AgentHistoryRun {
  return {
    runId: run.runId,
    status: run.status,
    input,
    answer: run.answer,
    traceId: run.traceId,
    latencyMs: run.latencyMs,
    providerId: null,
    providerModel: run.providerModel ?? null,
    totalTokens: run.totalTokens ?? null,
    toolCalls: run.toolCalls,
    createdAt: new Date().toISOString(),
    providerState: run.providerState,
    promptVersion: run.promptVersion ?? null,
    stopReason: run.stopReason ?? null,
    toolSteps: run.toolSteps ?? null,
  };
}
