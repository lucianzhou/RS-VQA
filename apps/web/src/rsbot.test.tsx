import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { PropsWithChildren } from "react";
import { describe, expect, it, vi } from "vitest";
import type { AgentHistoryRun, AgentSession, AgentSessionSummary } from "./types";
import { latestSessionForContext, rsBotProgress, useRsBotSession } from "./rsbot";

const runA: AgentHistoryRun = {
  runId: "run-a",
  status: "COMPLETED",
  input: "A 的问题",
  answer: "A 的回答",
  traceId: "trace-a",
  latencyMs: 120,
  providerId: "gemini",
  providerModel: "gemini-3.6-flash",
  totalTokens: 20,
  toolCalls: [],
  createdAt: "2026-07-27T01:00:00Z",
};

function summary(id: string, conversationId: string, updatedAt: string): AgentSessionSummary {
  return {
    id,
    title: `会话 ${id}`,
    contextType: "CONVERSATION",
    contextId: conversationId,
    contextLabel: conversationId,
    runCount: id === "session-a" ? 1 : 0,
    createdAt: updatedAt,
    updatedAt,
  };
}

function session(item: AgentSessionSummary, runs: AgentHistoryRun[] = []): AgentSession {
  const { runCount: _runCount, ...rest } = item;
  return { ...rest, runs, suggestedPrompts: [] };
}

function testWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe("RS-Bot contextual session persistence", () => {
  it("selects the latest returned session for the active conversation", () => {
    const sessions = [
      summary("session-old", "conversation-a", "2026-07-27T01:00:00Z"),
      summary("session-b", "conversation-b", "2026-07-27T03:00:00Z"),
      summary("session-new", "conversation-a", "2026-07-27T02:00:00Z"),
    ];

    expect(latestSessionForContext(sessions, { conversationId: "conversation-a" })?.id).toBe("session-new");
  });

  it("restores each conversation when switching A to B and back to A", async () => {
    const sessionA = summary("session-a", "conversation-a", "2026-07-27T02:00:00Z");
    const sessionB = summary("session-b", "conversation-b", "2026-07-27T01:00:00Z");
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/agent/sessions")) return jsonResponse([sessionA, sessionB]);
      if (url.endsWith("/api/v1/agent/sessions/session-a")) return jsonResponse(session(sessionA, [runA]));
      if (url.endsWith("/api/v1/agent/sessions/session-b")) return jsonResponse(session(sessionB));
      return jsonResponse({}, 404);
    }));

    const { result, rerender } = renderHook(
      ({ conversationId }) => useRsBotSession({ conversationId }),
      { wrapper: testWrapper(), initialProps: { conversationId: "conversation-a" } },
    );

    await waitFor(() => expect(result.current.runs[0]?.input).toBe("A 的问题"));
    rerender({ conversationId: "conversation-b" });
    await waitFor(() => expect(result.current.sessionId).toBe("session-b"));
    expect(result.current.runs).toHaveLength(0);
    rerender({ conversationId: "conversation-a" });
    await waitFor(() => expect(result.current.runs[0]?.input).toBe("A 的问题"));
  });

  it("creates one persisted session on the first question", async () => {
    const createdSummary = summary("session-created", "conversation-a", "2026-07-27T03:00:00Z");
    const createdSession = session(createdSummary);
    let createCount = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/v1/agent/sessions") && init?.method === "POST") {
        createCount += 1;
        return jsonResponse(createdSession);
      }
      if (url.endsWith("/api/v1/agent/runs/stream") && init?.method === "POST") {
        return new Response(`event: completed\ndata: ${JSON.stringify({
          runId: "run-created",
          status: "COMPLETED",
          answer: "已完成",
          traceId: "trace-created",
          latencyMs: 10,
          providerModel: "gemini-3.6-flash",
          toolCalls: [],
        })}\n\n`, { status: 200, headers: { "Content-Type": "text/event-stream" } });
      }
      if (url.endsWith("/api/v1/agent/sessions/session-created")) return jsonResponse(createdSession);
      if (url.endsWith("/api/v1/agent/sessions")) return jsonResponse([]);
      return jsonResponse({});
    }));

    const { result } = renderHook(
      () => useRsBotSession({ conversationId: "conversation-a" }),
      { wrapper: testWrapper() },
    );
    await waitFor(() => expect(result.current.isLoadingSession).toBe(false));
    await act(async () => {
      await result.current.ask("查询当前模型版本");
    });
    await waitFor(() => expect(result.current.isRunning).toBe(false));

    expect(createCount).toBe(1);
    expect(result.current.sessionId).toBe("session-created");
    expect(result.current.runs[0]?.input).toBe("查询当前模型版本");
  });
});

describe("RS-Bot progress stages", () => {
  it.each([
    ["accepted", "planning", "正在规划分析步骤", 0],
    ["tool_started", "tool", "正在调用已授权工具", 1],
    ["completed", "answer", "正在组织回答", 2],
  ])("maps %s to a truthful progress phase", (stage, key, label, activeIndex) => {
    expect(rsBotProgress(stage)).toEqual({ key, label, activeIndex });
  });

  it.each(["", "failed", "future_backend_stage"])("uses a neutral fallback for %s", (stage) => {
    expect(rsBotProgress(stage)).toEqual({ key: "neutral", label: "处理中", activeIndex: null });
  });
});

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
