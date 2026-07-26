import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createAgentSession, getAgentSession, listAgentSessions, runTrustedAgentStream } from "./api";
import type { AgentHistoryRun, AgentRun, AgentSession, AgentSessionSummary } from "./types";

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

function contextBinding(context: RsBotContext): Pick<AgentSessionSummary, "contextType" | "contextId"> | null {
  if (context.conversationId) return { contextType: "CONVERSATION", contextId: context.conversationId };
  if (context.projectId) return { contextType: "PROJECT", contextId: context.projectId };
  if (context.batchJobId) return { contextType: "BATCH_JOB", contextId: context.batchJobId };
  return context.sessionId ? null : { contextType: "WORKSPACE", contextId: null };
}

export function latestSessionForContext(
  sessions: AgentSessionSummary[],
  context: RsBotContext,
): AgentSessionSummary | undefined {
  const binding = contextBinding(context);
  if (!binding || !Array.isArray(sessions)) return undefined;
  return sessions
    .filter((item) => item.contextType === binding.contextType && item.contextId === binding.contextId)
    .reduce<AgentSessionSummary | undefined>(
      (latest, item) => !latest || item.updatedAt > latest.updatedAt ? item : latest,
      undefined,
    );
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
  const [transientRuns, setTransientRuns] = useState<AgentHistoryRun[]>([]);
  const [sessionOverride, setSessionOverride] = useState<{ contextKey: string; sessionId: string }>();
  const controller = useRef<AbortController | undefined>(undefined);
  const contextKey = [
    context.sessionId ?? "",
    context.projectId ?? "",
    context.conversationId ?? "",
    context.batchJobId ?? "",
  ].join(":");
  const binding = contextBinding(context);
  const sessions = useQuery({
    queryKey: ["agent-sessions"],
    queryFn: listAgentSessions,
    enabled: !context.sessionId && Boolean(binding),
  });
  const restoredSessionId = useMemo(
    () => latestSessionForContext(sessions.data ?? [], context)?.id,
    [context, sessions.data],
  );
  const effectiveSessionId = context.sessionId
    ?? (sessionOverride?.contextKey === contextKey ? sessionOverride.sessionId : undefined)
    ?? restoredSessionId;

  const session = useQuery({
    queryKey: ["agent-session", effectiveSessionId],
    queryFn: () => getAgentSession(effectiveSessionId!),
    enabled: Boolean(effectiveSessionId),
  });

  useEffect(() => () => controller.current?.abort(), []);
  useEffect(() => {
    controller.current?.abort();
    controller.current = undefined;
    setStage("");
    setPendingQuestion("");
    setTransientRuns([]);
  }, [contextKey]);

  const create = useMutation({
    mutationFn: () => createAgentSession({
      projectId: context.projectId,
      conversationId: context.conversationId,
      batchJobId: context.batchJobId,
    }),
    onSuccess: async (created: AgentSession) => {
      setSessionOverride({ contextKey, sessionId: created.id });
      queryClient.setQueryData(["agent-session", created.id], created);
      await queryClient.invalidateQueries({ queryKey: ["agent-sessions"] });
    },
  });

  const run = useMutation({
    mutationFn: ({ message, sessionId, signal }: { message: string; sessionId: string; signal: AbortSignal }) =>
      runTrustedAgentStream(
        { message, ...context, sessionId },
        (event) => setStage(event as RsBotStage),
        signal,
      ),
    onSuccess: (completed, variables) => {
      setTransientRuns((current) => current.some((item) => item.runId === completed.runId)
        ? current
        : [...current, toTranscriptRun(completed, variables.message)]);
    },
    onSettled: async (_data, _error, variables) => {
      controller.current = undefined;
      setPendingQuestion("");
      setStage("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["agent-session", variables.sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["agent-sessions"] }),
        queryClient.invalidateQueries({ queryKey: ["agent-actions", variables.sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["audit"] }),
      ]);
    },
  });

  const ask = useCallback(async (message: string) => {
    const value = message.trim();
    if (!value || run.isPending || create.isPending) return;
    const next = new AbortController();
    controller.current = next;
    setPendingQuestion(value);
    setStage("accepted");
    try {
      const sessionId = effectiveSessionId ?? (await create.mutateAsync()).id;
      run.mutate({ message: value, sessionId, signal: next.signal });
    } catch {
      controller.current = undefined;
      setPendingQuestion("");
      setStage("");
    }
  }, [create, effectiveSessionId, run]);

  const cancel = useCallback(() => {
    controller.current?.abort();
    controller.current = undefined;
    setStage("");
  }, []);

  const startNewSession = useCallback(async () => {
    if (run.isPending || create.isPending) return;
    controller.current?.abort();
    try {
      const created = await create.mutateAsync();
      setTransientRuns([]);
      setSessionOverride({ contextKey, sessionId: created.id });
    } catch {
      // The mutation error is exposed to the shared chat error state.
    }
  }, [contextKey, create, run.isPending]);

  const persistedRuns = session.data?.runs ?? [];
  const runs = [
    ...persistedRuns,
    ...transientRuns.filter((candidate) =>
      !persistedRuns.some((persisted) => persisted.runId === candidate.runId)
    ),
  ];

  return {
    sessionId: effectiveSessionId,
    session: session.data,
    isLoadingSession: (sessions.isPending && !context.sessionId) || (session.isPending && Boolean(effectiveSessionId)),
    sessionError: sessions.error?.message ?? session.error?.message,
    runs,
    lastRun: run.data,
    isRunning: create.isPending || run.isPending,
    error: create.error?.message ?? run.error?.message,
    stage,
    pendingQuestion,
    ask,
    cancel,
    startNewSession,
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
